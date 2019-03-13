package de.invesdwin.context.persistence.timeseries.timeseriesdb;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.NoSuchElementException;

import javax.annotation.concurrent.NotThreadSafe;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.SerializationException;

import de.invesdwin.context.ContextProperties;
import de.invesdwin.context.integration.streams.LZ4Streams;
import de.invesdwin.context.log.error.Err;
import de.invesdwin.util.collections.iterable.ACloseableIterator;
import de.invesdwin.util.collections.iterable.EmptyCloseableIterator;
import de.invesdwin.util.collections.iterable.ICloseableIterator;
import de.invesdwin.util.collections.iterable.IReverseCloseableIterable;
import de.invesdwin.util.collections.iterable.LimitingIterator;
import de.invesdwin.util.collections.iterable.buffer.BufferingIterator;
import de.invesdwin.util.lang.Objects;
import de.invesdwin.util.lang.UniqueNameGenerator;
import de.invesdwin.util.lang.finalizer.AFinalizer;
import ezdb.serde.Serde;

@NotThreadSafe
public class SerializingCollection<E> implements Collection<E>, IReverseCloseableIterable<E>, Serializable, Closeable {

    private static final int READ_ONLY_FILE_SIZE = Integer.MAX_VALUE;
    private static final UniqueNameGenerator UNIQUE_NAME_GENERATOR = new UniqueNameGenerator() {
        @Override
        protected long getInitialValue() {
            return 1;
        }
    };

    private int size;
    private final File file;
    private DataOutputStream fos;
    private boolean closed;
    private final Integer fixedLength = getFixedLength();
    private final Serde<E> serde = newSerde();

    public SerializingCollection(final String id) {
        this.file = new File(getTempFolder(), UNIQUE_NAME_GENERATOR.get(id.replace(":", "_")) + ".data");
        if (file.exists()) {
            throw new IllegalStateException("File [" + file.getAbsolutePath() + "] already exists!");
        }
    }

    public SerializingCollection(final File file, final boolean readOnly) {
        this.file = file;
        if (readOnly) {
            //allow deserializing only if file contains data already
            this.size = READ_ONLY_FILE_SIZE;
            this.closed = true;
        }
    }

    public File getFile() {
        return file;
    }

    private File getTempFolder() {
        final File tempFolder = new File(ContextProperties.TEMP_DIRECTORY, SerializingCollection.class.getSimpleName());
        try {
            FileUtils.forceMkdir(tempFolder);
        } catch (final IOException e) {
            throw Err.process(e);
        }
        return tempFolder;
    }

    private DataOutputStream getFos() {
        if (fos == null) {
            //Lazy init to prevent too many open files Exceptions
            if (closed) {
                throw new IllegalStateException("false expected");
            }
            try {
                fos = new DataOutputStream(newCompressor(newFileOutputStream(file)));
            } catch (final IOException e) {
                throw Err.process(e);
            }
        }
        return fos;
    }

    @Override
    public boolean add(final E element) {
        if (this.size == READ_ONLY_FILE_SIZE) {
            throw new IllegalStateException(
                    "File [" + file + "] is in read only mode since it contained data when it was opened!");
        }
        try {
            final byte[] bytes = serde.toBytes(element);
            if (bytes == null || bytes.length == 0) {
                throw new IllegalStateException("bytes should contain actual data: " + element);
            }
            final DataOutputStream fos = getFos();
            if (fixedLength == null) {
                fos.writeInt(bytes.length);
                fos.write(bytes);
            } else {
                if (bytes.length != fixedLength) {
                    throw new IllegalArgumentException(
                            "Serialized object [" + element + "] has unexpected byte length of [" + bytes.length
                                    + "] while fixed length [" + fixedLength + "] was expected!");
                }
                fos.write(bytes);
            }

        } catch (final IOException e) {
            throw Err.process(e);
        }
        size++;
        return true;
    }

    protected OutputStream newCompressor(final OutputStream out) {
        //LZ4HC is read optimized, you can write optimize by using fastCompressor()
        return LZ4Streams.newDefaultLZ4OutputStream(out);
    }

    protected InputStream newDecompressor(final InputStream inputStream) {
        return LZ4Streams.newDefaultLZ4InputStream(inputStream);
    }

    protected Serde<E> newSerde() {
        return new Serde<E>() {
            @Override
            public E fromBytes(final byte[] bytes) {
                return Objects.deserialize(bytes);
            }

            @Override
            public byte[] toBytes(final E obj) {
                return Objects.serialize((Serializable) obj);
            }
        };
    }

    /**
     * Override this to define a fixed length format and thus skip base64 encoding for better performance (though this
     * might behave badly for lists and other dynamic stuff; assertions will tell you someting is wrong
     */
    protected Integer getFixedLength() {
        return null;
    }

    /**
     * Closes this Iterable for more add() operations.
     */
    @Override
    public void close() {
        if (!closed) {
            IOUtils.closeQuietly(fos);
            fos = null;
            closed = true;
        }
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void clear() {
        if (file != null) {
            file.delete();
        }
    }

    @Override
    public ICloseableIterator<E> iterator() {
        if (size() > 0) {
            if (closed) {
                return newIterator();
            } else {
                try {
                    //need to flush contents so we can actually read them
                    fos.flush();
                } catch (final IOException e) {
                    throw new RuntimeException(e);
                }
                //we allow iteration up to the current size
                return new LimitingIterator<E>(newIterator(), size());
            }
        } else {
            return EmptyCloseableIterator.getInstance();
        }
    }

    @Override
    public ICloseableIterator<E> reverseIterator() {
        final BufferingIterator<E> reverseIterator = new BufferingIterator<E>();
        try (ICloseableIterator<E> iterator = iterator()) {
            while (true) {
                reverseIterator.prepend(iterator.next());
            }
        } catch (final NoSuchElementException e) {
            //ignore
        }
        return reverseIterator;
    }

    private ICloseableIterator<E> newIterator() {
        final ICloseableIterator<E> iterator;
        if (fixedLength != null) {
            iterator = new FixedLengthDeserializingIterator();
        } else {
            iterator = new DynamicLengthDeserializingIterator();
        }
        return iterator;
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public boolean isEmpty() {
        return size == 0;
    }

    @Override
    public boolean contains(final Object o) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Object[] toArray() {
        final List<E> list = new ArrayList<E>();
        for (final E e : this) {
            list.add(e);
        }
        return list.toArray();
    }

    @Override
    public <T> T[] toArray(final T[] a) {
        final List<E> list = new ArrayList<E>();
        for (final E e : this) {
            list.add(e);
        }
        return list.toArray(a);
    }

    @Override
    public boolean containsAll(final Collection<?> c) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean addAll(final Collection<? extends E> c) {
        return addAllIterable(c);
    }

    public boolean addAllIterable(final Iterable<? extends E> c) {
        boolean changed = false;
        for (final E e : c) {
            if (add(e)) {
                changed = true;
            }
        }
        return changed;
    }

    @Override
    public boolean retainAll(final Collection<?> c) {
        throw new UnsupportedOperationException();
    }

    protected InputStream newFileInputStream(final File file) throws FileNotFoundException {
        return new BufferedInputStream(new FileInputStream(file));
    }

    protected OutputStream newFileOutputStream(final File file) throws IOException {
        return new BufferedOutputStream(new FileOutputStream(file));
    }

    @NotThreadSafe
    private class DynamicLengthDeserializingIterator extends ACloseableIterator<E> {

        private final DynamicLengthDeserializingIteratorFinalizer<E> finalizer;

        {
            finalizer = new DynamicLengthDeserializingIteratorFinalizer<>();
            finalizer.register(this);
            try {
                finalizer.inputStream = new DataInputStream(newDecompressor(newFileInputStream(file)));
            } catch (final IOException e) {
                throw Err.process(e);
            }
        }

        @Override
        protected boolean innerHasNext() {
            if (finalizer.cachedElement != null) {
                return true;
            } else {
                try {
                    finalizer.cachedElement = readNext();
                    return finalizer.cachedElement != null;
                } catch (final SerializationException e) {
                    return false;
                }
            }
        }

        @SuppressWarnings({ "null" })
        private E readNext() {
            try {
                if (finalizer.closed) {
                    return (E) null;
                }
                final int size;
                try {
                    size = finalizer.inputStream.readInt();
                } catch (final EOFException e) {
                    finalizer.close();
                    return null;
                }
                final byte[] bytes = new byte[size];
                finalizer.inputStream.readFully(bytes);
                if (bytes.length == 0) {
                    throw new IllegalStateException("empty encoded entries should have been filtered by add()");
                }
                return serde.fromBytes(bytes);
            } catch (final IOException e) {
                throw Err.process(e);
            }
        }

        @SuppressWarnings("null")
        @Override
        protected E innerNext() {
            if (finalizer.cachedElement != null) {
                final E ret = finalizer.cachedElement;
                finalizer.cachedElement = (E) null;
                return ret;
            }
            return readNext();
        }

        @Override
        public void close() {
            super.close();
            finalizer.close();
        }

    }

    private static final class DynamicLengthDeserializingIteratorFinalizer<E> extends AFinalizer {
        private DataInputStream inputStream;
        private boolean closed;
        private E cachedElement;

        @Override
        protected void clean() {
            closed = true;
            try {
                inputStream.close();
                //free memory
                inputStream = null;
                cachedElement = null;
            } catch (final IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public boolean isClosed() {
            return closed;
        }
    }

    @NotThreadSafe
    private class FixedLengthDeserializingIterator extends ACloseableIterator<E> {

        private final FixedLengthDeserializingIteratorFinalizer<E> finalizer;

        {
            finalizer = new FixedLengthDeserializingIteratorFinalizer<>();
            finalizer.register(this);
            try {
                finalizer.inputStream = new DataInputStream(newDecompressor(newFileInputStream(file)));
                finalizer.byteBuffer = new byte[fixedLength];
            } catch (final IOException e) {
                throw Err.process(e);
            }
        }

        @Override
        protected boolean innerHasNext() {
            if (finalizer.cachedElement != null) {
                return true;
            } else {
                try {
                    finalizer.cachedElement = readNext();
                    return finalizer.cachedElement != null;
                } catch (final SerializationException e) {
                    return false;
                }
            }
        }

        @SuppressWarnings({ "null" })
        private E readNext() {
            if (finalizer.closed) {
                return (E) null;
            }
            try {
                finalizer.inputStream.readFully(finalizer.byteBuffer);
            } catch (final IOException e) {
                finalizer.close();
                return null;
            }
            return serde.fromBytes(finalizer.byteBuffer);
        }

        @SuppressWarnings("null")
        @Override
        protected E innerNext() {
            if (finalizer.cachedElement != null) {
                final E ret = finalizer.cachedElement;
                finalizer.cachedElement = (E) null;
                return ret;
            }
            return readNext();
        }

        @Override
        public void close() {
            super.close();
            finalizer.close();
        }

    }

    private static final class FixedLengthDeserializingIteratorFinalizer<E> extends AFinalizer {
        private DataInputStream inputStream;
        private byte[] byteBuffer;
        private boolean closed;

        private E cachedElement;

        @Override
        protected void clean() {
            closed = true;
            try {
                inputStream.close();
                //free memory
                inputStream = null;
                byteBuffer = null;
                cachedElement = null;
            } catch (final IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public boolean isClosed() {
            return closed;
        }
    }

    private void writeObject(final java.io.ObjectOutputStream stream) throws IOException {
        if (!closed) {
            throw new IllegalStateException("You need to close this instance before serializing it!");
        }
        stream.defaultWriteObject();
    }

    @Override
    public boolean remove(final Object o) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean removeAll(final Collection<?> c) {
        throw new UnsupportedOperationException();
    }

    public void flush() throws IOException {
        if (fos != null) {
            fos.flush();
        }
    }

}
