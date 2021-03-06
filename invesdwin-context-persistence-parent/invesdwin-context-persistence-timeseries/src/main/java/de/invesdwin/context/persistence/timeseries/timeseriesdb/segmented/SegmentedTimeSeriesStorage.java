package de.invesdwin.context.persistence.timeseries.timeseriesdb.segmented;

import java.io.File;

import javax.annotation.concurrent.ThreadSafe;

import de.invesdwin.context.persistence.timeseries.ezdb.ADelegateRangeTable;
import de.invesdwin.context.persistence.timeseries.timeseriesdb.storage.CorruptedTimeSeriesStorageException;
import de.invesdwin.context.persistence.timeseries.timeseriesdb.storage.TimeSeriesStorage;
import de.invesdwin.util.time.range.TimeRange;

@ThreadSafe
public class SegmentedTimeSeriesStorage extends TimeSeriesStorage {

    private final ADelegateRangeTable<String, TimeRange, SegmentStatus> segmentStatusTable;

    public SegmentedTimeSeriesStorage(final File directory) {
        super(directory);
        segmentStatusTable = new ADelegateRangeTable<String, TimeRange, SegmentStatus>("segmentStatusTable") {
            @Override
            protected boolean allowPutWithoutBatch() {
                return true;
            }

            @Override
            protected boolean allowHasNext() {
                return true;
            }

            @Override
            protected File getDirectory() {
                return directory;
            }

            @Override
            protected void onDeleteTableFinished() {
                throw new CorruptedTimeSeriesStorageException(getName());
            }
        };
    }

    public ADelegateRangeTable<String, TimeRange, SegmentStatus> getSegmentStatusTable() {
        return segmentStatusTable;
    }

    @Override
    public void close() {
        super.close();
        segmentStatusTable.close();
    }

}
