package de.invesdwin.context.persistence.timeseries.timeseriesdb;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.concurrent.NotThreadSafe;

import org.apache.commons.io.FileUtils;

import de.invesdwin.context.integration.retry.RetryLaterRuntimeException;
import de.invesdwin.context.integration.streams.LZ4Streams;
import de.invesdwin.util.assertions.Assertions;
import de.invesdwin.util.bean.tuple.Pair;
import de.invesdwin.util.collections.iterable.ACloseableIterator;
import de.invesdwin.util.collections.iterable.ASkippingIterable;
import de.invesdwin.util.collections.iterable.FlatteningIterable;
import de.invesdwin.util.collections.iterable.ICloseableIterable;
import de.invesdwin.util.collections.iterable.ICloseableIterator;
import de.invesdwin.util.collections.iterable.concurrent.AParallelChunkConsumerIterator;
import de.invesdwin.util.collections.iterable.concurrent.AProducerQueueIterator;
import de.invesdwin.util.concurrent.Executors;
import de.invesdwin.util.time.Instant;
import de.invesdwin.util.time.fdate.FDate;
import ezdb.serde.Serde;
import net.jpountz.lz4.LZ4BlockOutputStream;

@NotThreadSafe
public abstract class ATimeSeriesUpdater<K, V> {

    public static final boolean DEFAULT_SHOULD_WRITE_IN_PARALLEL = false;
    public static final int BATCH_FLUSH_INTERVAL = 10_000;
    public static final int BATCH_QUEUE_SIZE = 500_000 / BATCH_FLUSH_INTERVAL;
    public static final int BATCH_WRITER_THREADS = Executors.getCpuThreadPoolCount();

    private final Serde<V> valueSerde;
    private final ATimeSeriesDB<K, V> table;
    private final TimeSeriesStorageCache<K, V> lookupTable;
    private final File updateLockFile;

    private final K key;
    private FDate minTime = null;
    private FDate maxTime = null;
    private int count = 0;

    public ATimeSeriesUpdater(final K key, final ATimeSeriesDB<K, V> table) {
        if (key == null) {
            throw new NullPointerException("key should not be null");
        }
        this.key = key;
        this.valueSerde = table.getValueSerde();
        this.table = table;
        this.lookupTable = table.getLookupTableCache(key);
        this.updateLockFile = lookupTable.getUpdateLockFile();
    }

    public K getKey() {
        return key;
    }

    public FDate getMinTime() {
        return minTime;
    }

    public FDate getMaxTime() {
        return maxTime;
    }

    public int getCount() {
        return count;
    }

    public final boolean update() throws IncompleteUpdateFoundException {
        try {
            if (!table.getTableLock(key).writeLock().tryLock(1, TimeUnit.MINUTES)) {
                throw new RetryLaterRuntimeException("Write lock could not be acquired for table [" + table.getName()
                        + "] and key [" + key + "]. Please ensure all iterators are closed!");
            }
        } catch (final InterruptedException e1) {
            throw new RuntimeException(e1);
        }
        try {
            if (updateLockFile.exists()) {
                throw new IncompleteUpdateFoundException("Incomplete update found for table [" + table.getName()
                        + "] and key [" + key + "], need to clean everything up to restore all from scratch.");
            }
            try {
                FileUtils.touch(updateLockFile);
            } catch (final IOException e) {
                throw new RuntimeException(e);
            }
            final Instant updateStart = new Instant();
            onUpdateStart();
            doUpdate();
            onUpdateFinished(updateStart);
            Assertions.assertThat(updateLockFile.delete()).isTrue();
            return true;
        } finally {
            table.getTableLock(key).writeLock().unlock();
        }
    }

    private void doUpdate() {
        final Pair<FDate, List<V>> pair = lookupTable.prepareForUpdate(shouldRedoLastFile());
        final FDate updateFrom = pair.getFirst();
        final List<V> lastValues = pair.getSecond();
        Assertions.checkNotNull(lastValues);
        ICloseableIterable<? extends V> source = getSource(updateFrom);
        if (updateFrom != null) {
            //ensure we add no duplicate values
            source = new ASkippingIterable<V>(source) {
                @Override
                protected boolean skip(final V element) {
                    return extractTime(element).isBefore(updateFrom);
                }
            };
        }
        final FlatteningIterable<? extends V> flatteningSources = new FlatteningIterable<>(lastValues, source);
        try (ICloseableIterator<UpdateProgress> batchWriterProducer = new ICloseableIterator<UpdateProgress>() {

            private final ICloseableIterator<? extends V> elements = flatteningSources.iterator();

            @Override
            public boolean hasNext() {
                return elements.hasNext();
            }

            @Override
            public UpdateProgress next() {
                final UpdateProgress progress = new UpdateProgress();
                while (elements.hasNext()) {
                    final V element = elements.next();
                    if (progress.onElement(element)) {
                        return progress;
                    }
                }
                return progress;
            }

            @Override
            public void close() {
                elements.close();
            }
        }) {

            final AtomicInteger flushIndex = new AtomicInteger();
            if (shouldWriteInParallel()) {
                writeParallel(batchWriterProducer, flushIndex);
            } else {
                writeSerial(batchWriterProducer, flushIndex);
            }
        }

    }

    private void writeParallel(final ICloseableIterator<UpdateProgress> batchWriterProducer,
            final AtomicInteger flushIndex) {
        //do IO in a different thread than batch filling
        try (ACloseableIterator<UpdateProgress> batchProducer = new AProducerQueueIterator<UpdateProgress>(
                getClass().getSimpleName() + "_batchProducer_" + table.hashKeyToString(key), BATCH_QUEUE_SIZE) {
            @Override
            protected ICloseableIterator<ATimeSeriesUpdater<K, V>.UpdateProgress> newProducer() {
                return batchWriterProducer;
            }
        }) {
            try (ACloseableIterator<UpdateProgress> parallelConsumer = new AParallelChunkConsumerIterator<UpdateProgress, UpdateProgress>(
                    getClass().getSimpleName() + "_batchConsumer_" + table.hashKeyToString(key), batchProducer,
                    BATCH_WRITER_THREADS) {

                @Override
                protected UpdateProgress doWork(final UpdateProgress request) {
                    request.write(flushIndex.incrementAndGet());
                    return request;
                }
            }) {
                while (parallelConsumer.hasNext()) {
                    final UpdateProgress progress = parallelConsumer.next();
                    count += progress.getCount();
                    if (minTime == null) {
                        minTime = progress.getMinTime();
                    }
                    maxTime = progress.getMaxTime();
                }
            }
        }
    }

    private void writeSerial(final ICloseableIterator<UpdateProgress> batchWriterProducer,
            final AtomicInteger flushIndex) {
        while (batchWriterProducer.hasNext()) {
            final UpdateProgress progress = batchWriterProducer.next();
            progress.write(flushIndex.incrementAndGet());
            count += progress.getCount();
            if (minTime == null) {
                minTime = progress.getMinTime();
            }
            maxTime = progress.getMaxTime();
        }
    }

    protected boolean shouldWriteInParallel() {
        return DEFAULT_SHOULD_WRITE_IN_PARALLEL;
    }

    protected boolean shouldRedoLastFile() {
        return true;
    }

    protected abstract ICloseableIterable<? extends V> getSource(FDate updateFrom);

    protected abstract void onUpdateFinished(Instant updateStart);

    protected abstract void onUpdateStart();

    protected abstract FDate extractTime(V element);

    protected abstract FDate extractEndTime(V element);

    protected abstract void onFlush(int flushIndex, Instant flushStart, UpdateProgress updateProgress);

    protected LZ4BlockOutputStream newCompressor(final OutputStream out) {
        return newDefaultCompressor(out);
    }

    public static LZ4BlockOutputStream newDefaultCompressor(final OutputStream out) {
        return LZ4Streams.newLargeHighLZ4OutputStream(out);
    }

    public class UpdateProgress {

        private final List<V> batch = new ArrayList<V>(BATCH_FLUSH_INTERVAL);
        private long count;
        private FDate minTime;
        private FDate maxTime;

        public FDate getMinTime() {
            return minTime;
        }

        public FDate getMaxTime() {
            return maxTime;
        }

        public long getCount() {
            return count;
        }

        private boolean onElement(final V element) {
            final FDate time = extractTime(element);
            if (minTime == null) {
                minTime = time;
            }
            if (maxTime != null && maxTime.isAfter(time)) {
                throw new IllegalArgumentException(
                        "New element time [" + time + "] is not after or equal to previous element end time [" + maxTime
                                + "] for table [" + table.getName() + "] and key [" + key + "]");
            }
            final FDate endTime = extractEndTime(element);
            maxTime = endTime;
            batch.add(element);
            count++;
            return getCount() % BATCH_FLUSH_INTERVAL == 0;
        }

        private void write(final int flushIndex) {
            final Instant flushStart = new Instant();

            final File newFile = lookupTable.newFile(minTime);
            final SerializingCollection<V> collection = new SerializingCollection<V>(newFile, false) {
                @Override
                protected Serde<V> newSerde() {
                    return new Serde<V>() {

                        @Override
                        public V fromBytes(final byte[] bytes) {
                            throw new UnsupportedOperationException();
                        }

                        @Override
                        public byte[] toBytes(final V obj) {
                            return valueSerde.toBytes(obj);
                        }
                    };
                }

                @Override
                protected OutputStream newCompressor(final OutputStream out) {
                    return ATimeSeriesUpdater.this.newCompressor(out);
                }

                @Override
                protected Integer getFixedLength() {
                    return table.getFixedLength();
                }

            };
            V firstElement = null;
            V lastElement = null;
            try {
                for (final V element : batch) {
                    collection.add(element);
                    if (firstElement == null) {
                        firstElement = element;
                    }
                    lastElement = element;
                }
            } finally {
                collection.close();
            }
            lookupTable.finishFile(minTime, firstElement, lastElement);

            onFlush(flushIndex, flushStart, this);
        }

    }

}
