package de.invesdwin.context.persistence.leveldb.timeseries.segmented;

import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;

import javax.annotation.concurrent.ThreadSafe;

import org.apache.commons.lang3.SerializationException;

import com.google.common.base.Function;

import de.invesdwin.context.integration.retry.ARetryingRunnable;
import de.invesdwin.context.integration.retry.RetryLaterRuntimeException;
import de.invesdwin.context.integration.retry.RetryOriginator;
import de.invesdwin.context.log.Log;
import de.invesdwin.context.persistence.leveldb.ezdb.ADelegateRangeTable;
import de.invesdwin.context.persistence.leveldb.ezdb.ADelegateRangeTable.DelegateTableIterator;
import de.invesdwin.context.persistence.leveldb.timeseries.IncompleteUpdateFoundException;
import de.invesdwin.context.persistence.leveldb.timeseries.TimeSeriesStorageCache;
import de.invesdwin.context.persistence.leveldb.timeseries.storage.ChunkValue;
import de.invesdwin.context.persistence.leveldb.timeseries.storage.ShiftUnitsRangeKey;
import de.invesdwin.context.persistence.leveldb.timeseries.storage.SingleValue;
import de.invesdwin.context.persistence.leveldb.timeseries.updater.ALoggingTimeSeriesUpdater;
import de.invesdwin.util.bean.tuple.Pair;
import de.invesdwin.util.collections.eviction.EvictionMode;
import de.invesdwin.util.collections.iterable.ASkippingIterable;
import de.invesdwin.util.collections.iterable.ATransformingCloseableIterable;
import de.invesdwin.util.collections.iterable.FlatteningIterable;
import de.invesdwin.util.collections.iterable.ICloseableIterable;
import de.invesdwin.util.collections.iterable.ICloseableIterator;
import de.invesdwin.util.collections.loadingcache.ALoadingCache;
import de.invesdwin.util.collections.loadingcache.historical.AHistoricalCache;
import de.invesdwin.util.error.FastNoSuchElementException;
import de.invesdwin.util.error.Throwables;
import de.invesdwin.util.time.TimeRange;
import de.invesdwin.util.time.fdate.FDate;
import de.invesdwin.util.time.fdate.FDates;
import ezdb.TableRow;
import ezdb.serde.Serde;

@ThreadSafe
public abstract class ASegmentedTimeSeriesStorageCache<K, V> {
    public static final Integer MAXIMUM_SIZE = TimeSeriesStorageCache.MAXIMUM_SIZE;
    public static final EvictionMode EVICTION_MODE = TimeSeriesStorageCache.EVICTION_MODE;

    private final ALoadingCache<FDate, V> latestValueLookupCache = new ALoadingCache<FDate, V>() {

        @Override
        protected Integer getInitialMaximumSize() {
            return MAXIMUM_SIZE;
        }

        @Override
        protected EvictionMode getEvictionMode() {
            return EVICTION_MODE;
        }

        @Override
        protected V loadValue(final FDate date) {
            final SingleValue value = storage.getLatestValueLookupTable().getOrLoad(hashKey, date,
                    new Function<Pair<String, FDate>, SingleValue>() {

                        @Override
                        public SingleValue apply(final Pair<String, FDate> input) {
                            final FDate firstAvailableSegmentFrom = getFirstAvailableSegmentFrom(key);
                            final FDate lastAvailableSegmentTo = getLastAvailableSegmentTo(key);
                            final FDate adjFrom = firstAvailableSegmentFrom;
                            final FDate adjTo = FDates.min(input.getSecond(), lastAvailableSegmentTo);
                            final ICloseableIterable<TimeRange> segmentsReverse = getSegmentsReverse(adjFrom, adjTo);
                            try (ICloseableIterator<TimeRange> it = segmentsReverse.iterator()) {
                                V latestValue = null;
                                while (it.hasNext()) {
                                    final TimeRange segment = it.next();
                                    final SegmentedKey<K> segmentedKey = new SegmentedKey<K>(key, segment);
                                    maybeInitSegment(segmentedKey);
                                    final V newValue = segmentedTable.getLatestValue(segmentedKey, date);
                                    final FDate newValueTime = segmentedTable.extractTime(newValue);
                                    if (newValueTime.isAfter(date)) {
                                        /*
                                         * even if we got the first value in this segment and it is after the desired
                                         * key we just continue to the beginning to search for an earlier value until we
                                         * reach the overall firstValue
                                         */
                                        break;
                                    } else {
                                        latestValue = newValue;
                                    }
                                }
                                if (latestValue == null) {
                                    latestValue = getFirstValue();
                                }
                                return new SingleValue(valueSerde, latestValue);
                            }
                        }
                    });
            if (value == null) {
                return null;
            }
            return value.getValue(valueSerde);
        }
    };
    private final ALoadingCache<Pair<FDate, Integer>, V> previousValueLookupCache = new ALoadingCache<Pair<FDate, Integer>, V>() {

        @Override
        protected Integer getInitialMaximumSize() {
            return MAXIMUM_SIZE;
        }

        @Override
        protected EvictionMode getEvictionMode() {
            return EVICTION_MODE;
        }

        @Override
        protected V loadValue(final Pair<FDate, Integer> loadKey) {
            final FDate date = loadKey.getFirst();
            final int shiftBackUnits = loadKey.getSecond();
            final SingleValue value = storage.getPreviousValueLookupTable().getOrLoad(hashKey,
                    new ShiftUnitsRangeKey(date, shiftBackUnits),
                    new Function<Pair<String, ShiftUnitsRangeKey>, SingleValue>() {

                        @Override
                        public SingleValue apply(final Pair<String, ShiftUnitsRangeKey> input) {
                            final FDate date = loadKey.getFirst();
                            final int shiftBackUnits = loadKey.getSecond();
                            V previousValue = null;
                            try (ICloseableIterator<V> rangeValuesReverse = readRangeValuesReverse(date, null)
                                    .iterator()) {
                                for (int i = 0; i < shiftBackUnits; i++) {
                                    previousValue = rangeValuesReverse.next();
                                }
                            } catch (final NoSuchElementException e) {
                                //ignore
                            }
                            return new SingleValue(valueSerde, previousValue);
                        }
                    });
            return value.getValue(valueSerde);
        }
    };
    private final ALoadingCache<Pair<FDate, Integer>, V> nextValueLookupCache = new ALoadingCache<Pair<FDate, Integer>, V>() {

        @Override
        protected Integer getInitialMaximumSize() {
            return MAXIMUM_SIZE;
        }

        @Override
        protected EvictionMode getEvictionMode() {
            return EVICTION_MODE;
        }

        @Override
        protected V loadValue(final Pair<FDate, Integer> loadKey) {
            final FDate date = loadKey.getFirst();
            final int shiftForwardUnits = loadKey.getSecond();
            final SingleValue value = storage.getNextValueLookupTable().getOrLoad(hashKey,
                    new ShiftUnitsRangeKey(date, shiftForwardUnits),
                    new Function<Pair<String, ShiftUnitsRangeKey>, SingleValue>() {

                        @Override
                        public SingleValue apply(final Pair<String, ShiftUnitsRangeKey> input) {
                            final FDate date = loadKey.getFirst();
                            final int shiftForwardUnits = loadKey.getSecond();
                            V nextValue = null;
                            try (ICloseableIterator<V> rangeValues = readRangeValues(date, null).iterator()) {
                                for (int i = 0; i < shiftForwardUnits; i++) {
                                    nextValue = rangeValues.next();
                                }
                            } catch (final NoSuchElementException e) {
                                //ignore
                            }
                            return new SingleValue(valueSerde, nextValue);
                        }
                    });
            return value.getValue(valueSerde);
        }
    };

    private volatile Optional<V> cachedFirstValue;
    private volatile Optional<V> cachedLastValue;
    private final Log log = new Log(this);

    private final AHistoricalCache<TimeRange> segmentFinder;
    private final ASegmentedTimeSeriesDB<K, V>.SegmentedTable segmentedTable;
    private final SegmentedTimeSeriesStorage storage;
    private final K key;
    private final String hashKey;
    private final Serde<V> valueSerde;

    public ASegmentedTimeSeriesStorageCache(final ASegmentedTimeSeriesDB<K, V>.SegmentedTable segmentedTable,
            final SegmentedTimeSeriesStorage storage, final K key, final String hashKey,
            final AHistoricalCache<TimeRange> segmentFinder) {
        this.storage = storage;
        this.segmentedTable = segmentedTable;
        this.key = key;
        this.hashKey = hashKey;
        this.segmentFinder = segmentFinder;
        this.valueSerde = segmentedTable.getValueSerde();
    }

    public ICloseableIterable<V> readRangeValues(final FDate from, final FDate to) {
        final FDate firstAvailableSegmentFrom = getFirstAvailableSegmentFrom(key);
        final FDate lastAvailableSegmentTo = getLastAvailableSegmentTo(key);
        //adjust dates directly to prevent unnecessary segment calculations
        final FDate adjFrom = FDates.max(from, firstAvailableSegmentFrom);
        final FDate adjTo = FDates.min(to, lastAvailableSegmentTo);
        final ICloseableIterable<TimeRange> segments = getSegments(adjFrom, adjTo);
        final ATransformingCloseableIterable<TimeRange, ICloseableIterable<V>> segmentQueries = new ATransformingCloseableIterable<TimeRange, ICloseableIterable<V>>(
                segments) {
            @Override
            protected ICloseableIterable<V> transform(final TimeRange value) {
                return new ICloseableIterable<V>() {
                    @Override
                    public ICloseableIterator<V> iterator() {
                        final SegmentedKey<K> segmentedKey = new SegmentedKey<K>(key, value);
                        maybeInitSegment(segmentedKey);
                        final FDate segmentAdjFrom = FDates.max(adjFrom, value.getFrom());
                        final FDate segmentAdjTo = FDates.min(adjTo, value.getTo());
                        return segmentedTable.rangeValues(segmentedKey, segmentAdjFrom, segmentAdjTo);
                    }
                };
            }
        };
        final ICloseableIterable<V> rangeValues = new FlatteningIterable<V>(segmentQueries);
        return rangeValues;
    }

    private ICloseableIterable<TimeRange> getSegments(final FDate adjFrom, final FDate adjTo) {
        final ICloseableIterable<TimeRange> segments = segmentFinder.query().getValues(adjFrom, adjTo);
        final ASkippingIterable<TimeRange> filteredSegments = new ASkippingIterable<TimeRange>(segments) {
            @Override
            protected boolean skip(final TimeRange element) {
                //though additionally skip ranges that exceed the available dates
                final FDate segmentTo = element.getTo();
                if (segmentTo.isBefore(adjFrom)) {
                    throw new IllegalStateException(
                            "segmentTo [" + segmentTo + "] should not be before adjFrom [" + adjFrom + "]");
                }
                if (segmentTo.isAfter(adjTo)) {
                    //no need to continue going higher
                    throw new FastNoSuchElementException("ASegmentedTimeSeriesStorageCache getSegments end reached");
                }
                return false;
            }
        };
        return filteredSegments;
    }

    private void maybeInitSegment(final SegmentedKey<K> segmentedKey) {
        //1. check segment status in series storage
        final ReadWriteLock segmentTableLock = segmentedTable.getTableLock(segmentedKey);
        /*
         * We need this synchronized block so that we don't collide on the write lock not being possible to be acquired
         * after 1 minute. The ReadWriteLock object should be safe to lock via synchronized keyword since no internal
         * synchronization occurs on that object itself
         */
        synchronized (segmentTableLock) {
            final SegmentStatus status = getSegmentStatusWithReadLock(segmentedKey, segmentTableLock);
            //2. if not existing or false, set status to false -> start segment update -> after update set status to true
            if (status == null || status == SegmentStatus.INITIALIZING) {
                final Lock segmentWriteLock = segmentTableLock.writeLock();
                try {
                    if (!segmentWriteLock.tryLock(1, TimeUnit.MINUTES)) {
                        /*
                         * should not happen here because segment did not yet exist. Though if it happens we would
                         * rather like an exception instead of a deadlock!
                         */
                        throw new RetryLaterRuntimeException(
                                "Write lock could not be acquired for table [" + segmentedTable.getName()
                                        + "] and key [" + segmentedKey + "]. Please ensure all iterators are closed!");
                    }
                } catch (final InterruptedException e1) {
                    throw new RuntimeException(e1);
                }
                try {
                    // no double checked locking required between read and write lock here because of the outer synchronized block
                    if (status == SegmentStatus.INITIALIZING) {
                        //initialization got aborted, retry from a fresh state
                        segmentedTable.deleteRange(segmentedKey);
                        storage.getSegmentStatusTable().delete(hashKey, segmentedKey.getSegment());
                    }
                    //throw error if a segment is being updated that is beyond the lastAvailableSegmentTo
                    final FDate segmentFrom = segmentedKey.getSegment().getTo();
                    final FDate firstAvailableSegmentFrom = getFirstAvailableSegmentFrom(segmentedKey.getKey());
                    if (segmentFrom.isBefore(firstAvailableSegmentFrom)) {
                        throw new IllegalStateException(segmentedKey + ": segmentFrom [" + segmentFrom
                                + "] should not be before firstAvailableSegmentFrom [" + firstAvailableSegmentFrom
                                + "]");
                    }
                    final FDate segmentTo = segmentedKey.getSegment().getTo();
                    final FDate lastAvailableSegmentTo = getLastAvailableSegmentTo(segmentedKey.getKey());
                    if (segmentTo.isAfter(lastAvailableSegmentTo)) {
                        throw new IllegalStateException(segmentedKey + ": segmentTo [" + segmentTo
                                + "] should not be after lastAvailableSegmentTo [" + lastAvailableSegmentTo + "]");
                    }
                    storage.getSegmentStatusTable().put(hashKey, segmentedKey.getSegment(), SegmentStatus.INITIALIZING);
                    initSegmentRetry(segmentedKey);
                    if (segmentedTable.isEmptyOrInconsistent(segmentedKey)) {
                        throw new IllegalStateException("Initialization of segment [" + segmentedKey
                                + "] should have added at least one entry");
                    }
                    storage.getSegmentStatusTable().put(hashKey, segmentedKey.getSegment(), SegmentStatus.COMPLETE);
                } finally {
                    segmentWriteLock.unlock();
                }
            }
        }
        //3. if true do nothing
    }

    private SegmentStatus getSegmentStatusWithReadLock(final SegmentedKey<K> segmentedKey,
            final ReadWriteLock segmentTableLock) {
        final Lock segmentReadLock = segmentTableLock.readLock();
        segmentReadLock.lock();
        try {
            return storage.getSegmentStatusTable().get(hashKey, segmentedKey.getSegment());
        } finally {
            segmentReadLock.unlock();
        }
    }

    private void initSegmentRetry(final SegmentedKey<K> segmentedKey) {
        new ARetryingRunnable(new RetryOriginator(ASegmentedTimeSeriesDB.class, "initSegment", segmentedKey)) {
            @Override
            protected void runRetryable() throws Exception {
                initSegment(segmentedKey);
            }
        }.run();
    }

    private void initSegment(final SegmentedKey<K> segmentedKey) {
        try {
            final ALoggingTimeSeriesUpdater<SegmentedKey<K>, V> updater = new ALoggingTimeSeriesUpdater<SegmentedKey<K>, V>(
                    segmentedKey, segmentedTable, log) {

                @Override
                protected ICloseableIterable<? extends V> getSource(final FDate updateFrom) {
                    return downloadSegmentElements(segmentedKey.getKey(), segmentedKey.getSegment().getFrom(),
                            segmentedKey.getSegment().getTo());
                }

                @Override
                protected FDate extractTime(final V element) {
                    return segmentedTable.extractTime(element);
                }

                @Override
                protected FDate extractEndTime(final V element) {
                    return segmentedTable.extractEndTime(element);
                }

                @Override
                protected String keyToString(final SegmentedKey<K> key) {
                    return segmentedTable.hashKeyToString(segmentedKey);
                }

                @Override
                protected String getElementsName() {
                    return "segment values";
                }

            };
            //write lock is reentrant
            updater.update();
            final FDate minTime = updater.getMinTime();
            final FDate segmentFrom = segmentedKey.getSegment().getFrom();
            if (minTime.isBefore(segmentFrom)) {
                throw new IllegalStateException(segmentedKey + ": minTime [" + minTime
                        + "] should not be before segmentFrom [" + segmentFrom + "]");
            }
            final FDate maxTime = updater.getMaxTime();
            final FDate segmentTo = segmentedKey.getSegment().getTo();
            if (maxTime.isAfter(segmentTo)) {
                throw new IllegalStateException(segmentedKey + ": maxTime [" + maxTime
                        + "] should not be before segmentTo [" + segmentTo + "]");
            }
        } catch (final IncompleteUpdateFoundException e) {
            segmentedTable.deleteRange(new SegmentedKey<K>(segmentedKey.getKey(), segmentedKey.getSegment()));
            throw new RetryLaterRuntimeException(e);
        }
    }

    protected abstract ICloseableIterable<? extends V> downloadSegmentElements(K key, FDate from, FDate to);

    protected abstract FDate getLastAvailableSegmentTo(K key);

    protected abstract FDate getFirstAvailableSegmentFrom(K key);

    protected ICloseableIterable<V> readRangeValuesReverse(final FDate from, final FDate to) {
        final FDate firstAvailableSegmentFrom = getFirstAvailableSegmentFrom(key);
        final FDate lastAvailableSegmentTo = getLastAvailableSegmentTo(key);
        //adjust dates directly to prevent unnecessary segment calculations
        final FDate adjFrom = FDates.max(from, firstAvailableSegmentFrom);
        final FDate adjTo = FDates.min(to, lastAvailableSegmentTo);
        final ICloseableIterable<TimeRange> filteredSegments = getSegmentsReverse(adjFrom, adjTo);
        final ATransformingCloseableIterable<TimeRange, ICloseableIterable<V>> segmentQueries = new ATransformingCloseableIterable<TimeRange, ICloseableIterable<V>>(
                filteredSegments) {
            @Override
            protected ICloseableIterable<V> transform(final TimeRange value) {
                return new ICloseableIterable<V>() {
                    @Override
                    public ICloseableIterator<V> iterator() {
                        final SegmentedKey<K> segmentedKey = new SegmentedKey<K>(key, value);
                        maybeInitSegment(segmentedKey);
                        final FDate segmentAdjFrom = FDates.max(adjFrom, value.getFrom());
                        final FDate segmentAdjTo = FDates.min(adjTo, value.getTo());
                        return segmentedTable.rangeReverseValues(segmentedKey, segmentAdjFrom, segmentAdjTo);
                    }

                };
            }
        };
        final ICloseableIterable<V> rangeValues = new FlatteningIterable<V>(segmentQueries);
        return rangeValues;
    }

    private ICloseableIterable<TimeRange> getSegmentsReverse(final FDate adjFrom, final FDate adjTo) {
        final ICloseableIterable<TimeRange> segments = new ICloseableIterable<TimeRange>() {
            @Override
            public ICloseableIterator<TimeRange> iterator() {
                return new ICloseableIterator<TimeRange>() {

                    private TimeRange curSegment = segmentFinder.query().getValue(adjTo);

                    @Override
                    public boolean hasNext() {
                        return curSegment.getTo().isAfter(adjFrom);
                    }

                    @Override
                    public TimeRange next() {
                        final TimeRange next = curSegment;
                        //get one segment earlier
                        curSegment = segmentFinder.query().getValue(curSegment.getFrom().addMilliseconds(-1));
                        return next;
                    }

                    @Override
                    public void close() {
                        curSegment = new TimeRange(FDate.MIN_DATE, FDate.MIN_DATE);
                    }
                };
            }
        };
        final ASkippingIterable<TimeRange> filteredSegments = new ASkippingIterable<TimeRange>(segments) {
            @Override
            protected boolean skip(final TimeRange element) {
                //though additionally skip ranges that exceed the available dates
                final FDate segmentTo = element.getTo();
                if (segmentTo.isBefore(adjFrom)) {
                    //no need to continue going lower
                    throw new FastNoSuchElementException("ASegmentedTimeSeriesStorageCache getSegments end reached");
                }
                //skip last value and continue with earlier ones
                return segmentTo.isAfter(adjTo);
            }
        };
        return filteredSegments;
    }

    public synchronized void deleteAll() {
        final ADelegateRangeTable<String, TimeRange, SegmentStatus> segmentStatusTable = storage
                .getSegmentStatusTable();
        try (DelegateTableIterator<String, TimeRange, SegmentStatus> range = segmentStatusTable.range(hashKey)) {
            while (true) {
                final TableRow<String, TimeRange, SegmentStatus> row = range.next();
                segmentedTable.deleteRange(new SegmentedKey<K>(key, row.getRangeKey()));
            }
        } catch (final NoSuchElementException e) {
            //end reached
        }
        segmentStatusTable.deleteRange(hashKey);
        storage.getLatestValueLookupTable().deleteRange(hashKey);
        storage.getNextValueLookupTable().deleteRange(hashKey);
        storage.getPreviousValueLookupTable().deleteRange(hashKey);
        clearCaches();
    }

    private void clearCaches() {
        latestValueLookupCache.clear();
        nextValueLookupCache.clear();
        previousValueLookupCache.clear();
        cachedFirstValue = null;
        cachedLastValue = null;
    }

    public V getLatestValue(final FDate date) {
        return latestValueLookupCache.get(date);
    }

    public V getPreviousValue(final FDate date, final int shiftBackUnits) {
        assertShiftUnitsPositiveNonZero(shiftBackUnits);
        return previousValueLookupCache.get(Pair.of(date, shiftBackUnits));
    }

    public V getNextValue(final FDate date, final int shiftForwardUnits) {
        assertShiftUnitsPositiveNonZero(shiftForwardUnits);
        return nextValueLookupCache.get(Pair.of(date, shiftForwardUnits));
    }

    public synchronized void prepareForUpdate() {
        final FDate lastTime = segmentedTable.extractTime(getLastValue());
        if (lastTime != null) {
            storage.getLatestValueLookupTable().deleteRange(hashKey, lastTime);
            storage.getNextValueLookupTable().deleteRange(hashKey); //we cannot be sure here about the date since shift keys can be arbitrarily large
            storage.getPreviousValueLookupTable().deleteRange(hashKey, new ShiftUnitsRangeKey(lastTime, 0));
        }
        clearCaches();
    }

    private void assertShiftUnitsPositiveNonZero(final int shiftUnits) {
        if (shiftUnits <= 0) {
            throw new IllegalArgumentException("shiftUnits needs to be a positive non zero value: " + shiftUnits);
        }
    }

    public V getFirstValue() {
        if (cachedFirstValue == null) {
            final FDate firstAvailableSegmentFrom = getFirstAvailableSegmentFrom(key);
            final TimeRange segment = segmentFinder.query().getValue(firstAvailableSegmentFrom);
            final SegmentedKey<K> segmentedKey = new SegmentedKey<K>(key, segment);
            maybeInitSegment(segmentedKey);
            final String segmentedHashKey = segmentedTable.hashKeyToString(segmentedKey);
            final ChunkValue latestValue = storage.getFileLookupTable().getLatestValue(segmentedHashKey,
                    FDate.MIN_DATE);
            final V firstValue;
            if (latestValue == null) {
                firstValue = null;
            } else {
                firstValue = latestValue.getFirstValue(valueSerde);
            }
            cachedFirstValue = Optional.ofNullable(firstValue);
        }
        return cachedFirstValue.orElse(null);
    }

    public V getLastValue() {
        if (cachedLastValue == null) {
            final FDate lastAvailableSegmentTo = getLastAvailableSegmentTo(key);
            final TimeRange segment = segmentFinder.query().getValue(lastAvailableSegmentTo);
            final SegmentedKey<K> segmentedKey = new SegmentedKey<K>(key, segment);
            maybeInitSegment(segmentedKey);
            final String segmentedHashKey = segmentedTable.hashKeyToString(segmentedKey);
            final ChunkValue latestValue = storage.getFileLookupTable().getLatestValue(segmentedHashKey,
                    FDate.MAX_DATE);
            final V lastValue;
            if (latestValue == null) {
                lastValue = null;
            } else {
                lastValue = latestValue.getLastValue(valueSerde);
            }
            cachedLastValue = Optional.ofNullable(lastValue);
        }
        return cachedLastValue.orElse(null);
    }

    public boolean isEmptyOrInconsistent() {
        try {
            getFirstValue();
            getLastValue();
        } catch (final Throwable t) {
            if (Throwables.isCausedByType(t, SerializationException.class)) {
                //e.g. fst: unable to find class for code 88 after version upgrade
                log.warn("Table data for [%s] is inconsistent and needs to be reset. Exception during getLastValue: %s",
                        hashKey, t.toString());
                return true;
            } else {
                //unexpected exception, since RemoteFastSerializingSerde only throws SerializingException
                throw Throwables.propagate(t);
            }
        }
        boolean empty = true;
        final ADelegateRangeTable<String, TimeRange, SegmentStatus> segmentsTable = storage.getSegmentStatusTable();
        try (DelegateTableIterator<String, TimeRange, SegmentStatus> range = segmentsTable.range(hashKey)) {
            while (true) {
                final TableRow<String, TimeRange, SegmentStatus> row = range.next();
                final SegmentStatus status = row.getValue();
                if (status == SegmentStatus.COMPLETE) {
                    if (segmentedTable.isEmptyOrInconsistent(new SegmentedKey<K>(key, row.getRangeKey()))) {
                        return true;
                    }
                }
                empty = false;
            }
        } catch (final NoSuchElementException e) {
            //end reached
        }
        return empty;
    }

}