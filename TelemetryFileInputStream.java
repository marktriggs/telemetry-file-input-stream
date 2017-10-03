//
// A FileInputStream that logs throughput (KB/s) of reads.
//
// (Nothing really file-specific about most of it, actually--could easily be
// adapted for an InputStream instead)
//
// Makes reasonable attempts to be robust and low-overhead.  Here's the plan:
//
//   * Each TelemetryFileInputStream instance logs the size of each read to a
//     shared Telemetry instance.  Right now that means taking a mutex, but it
//     isn't held for long.  The observation is written to a circular buffer and
//     we cross our fingers that we don't run out of space.
//
//   * An aggregation thread wakes up periodically (1 second by default), grabs
//     the mutex and tallies up the observations seen in the last second.  The
//     aggregated "timestep" total is written to a second array, and the
//     circular buffer is cleared.
//
//   * When we have accumulated enough timestep values to cover a report period,
//     we publish a copy of the array (via AtomicReference).  The goal here is
//     to decouple the (critical path) aggregation process from IO.
//
//   * A third reporting thread wakes up periodically and checks to see whether a
//     new report has been published.  If so, it logs it.
//
// If the circular buffer fills up, it just wraps around but shouldn't cause
// problems.  A warning is logged if that happens and the affected stats are
// skipped.
//
// If logging stalls for some reason, it shouldn't matter: the aggregation
// thread will just publish reports that nobody ever reads.  Some people make
// good careers doing that.

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;


public class TelemetryFileInputStream extends FileInputStream {

    // The maximum number of observations you're expecting to receive during
    // `timestepPeriod`.  If you exceed this we'll wrap around and lose
    // data, but that case is detected and the report is discarded.
    //
    private static final int MAX_OBSERVATION_COUNT = 1000000;

    // How often we'll log some stats
    private static final int REPORT_PERIOD = 60000;

    // How often we'll roll up the observations seen so far.  Bigger number
    // means lower overhead but longer pauses.
    private static final int TIMESTEP_PERIOD = 1000;


    private static Telemetry t = new Telemetry(TIMESTEP_PERIOD, REPORT_PERIOD, MAX_OBSERVATION_COUNT);

    public TelemetryFileInputStream(String s) throws FileNotFoundException {
        super(s);
    }

    public TelemetryFileInputStream(File f) throws FileNotFoundException {
        super(f);
    }

    public TelemetryFileInputStream(FileDescriptor f) throws FileNotFoundException {
        super(f);
    }


    // NOTE: We implicitly assume here that read(byte[]) doesn't call
    // read(byte[], int, int) to do its work.  That's currently true, but if
    // it changed we would end up double-counting!
    //
    public int read(byte[] b) throws IOException {
        int result;

        t.readPending();
        try {
            result = super.read(b);
            t.addObservation(result);
        } finally {
            t.readComplete();
        }

        return result;
    }


    public int read(byte[] b, int off, int len) throws IOException {
        int result;

        t.readPending();
        try {
            result = super.read(b, off, len);
            t.addObservation(result);
        } finally {
            t.readComplete();
        }

        return result;
    }


    // The magic...
    private static class Telemetry {
        private final int NO_REPORT_YET = -1;

        private final Object mutex = new Object();
        private int lastWritePos = -1;
        private boolean overflowed = false;

        private int maxObservationCount;
        private int reportPeriod;
        private int timestepPeriod;
        private int timestepsPerReport;
        private long[] observations;

        private AtomicLong lastReportTime = new AtomicLong(NO_REPORT_YET);
        private AtomicLong readsPending = new AtomicLong(0);
        private AtomicReference<TelemetryReport> lastReport = new AtomicReference<>();
        private Semaphore reportReadySemaphore = new Semaphore(0);


        public Telemetry (int timestepPeriod, int reportPeriod, int maxObservationCount) {
            this.timestepPeriod = timestepPeriod;
            this.reportPeriod = reportPeriod;
            this.maxObservationCount = maxObservationCount;
            this.observations = new long[maxObservationCount];

            timestepsPerReport = (reportPeriod / timestepPeriod);

            Thread aggregation = new Thread(() -> { runAggregationLoop(); });
            aggregation.setDaemon(true);
            aggregation.start();

            Thread report = new Thread(() -> { runReportLoop(); });
            report.setDaemon(true);
            report.start();
        }

        public void readPending() {
            readsPending.incrementAndGet();
        }

        public void readComplete() {
            readsPending.decrementAndGet();
        }

        // Log a single observation and get out of the way as quickly as we can.
        public void addObservation(int observation) {
            synchronized(mutex) {
                lastWritePos++;

                if (lastWritePos >= MAX_OBSERVATION_COUNT) {
                    overflowed = true;
                    lastWritePos = 0;
                }

                observations[lastWritePos] = observation;
            }
        }

        // Dart in and roll up the observations we've seen in the last timestep.
        // Periodically publishes a report to be logged.  On the critical path,
        // since it holds up observations being written while running.
        private void runAggregationLoop() {
            int currentTimestep = -1;
            TelemetryReport report = new TelemetryReport(timestepsPerReport);

            while (true) {
                currentTimestep++;

                try {
                    Thread.sleep(timestepPeriod);
                } catch (InterruptedException e) {
                    break;
                }

                synchronized (mutex) {
                    if (overflowed) {
                        System.err.println("WARNING: overflowed circular buffer.  Skipping this set of observations");
                        overflowed = false;

                        report.observationSums[currentTimestep] = -1;
                        report.readsPendingCounts[currentTimestep] = -1;
                    } else {
                        report.readsPendingCounts[currentTimestep] = readsPending.get();
                        report.readsCompletedCount += lastWritePos + 1;

                        for (int i = 0; i <= lastWritePos; i++) {
                            report.observationSums[currentTimestep] += observations[i];
                        }

                        // The next timestep will write at 0
                        lastWritePos = -1;
                    }

                    // Publish our report for the last `reportPeriod` if we've
                    // got a full set.
                    if (currentTimestep + 1 == timestepsPerReport) {
                        lastReport.set(report);
                        lastReportTime.set(System.currentTimeMillis());
                        reportReadySemaphore.release();

                        currentTimestep = -1;
                        report = new TelemetryReport(timestepsPerReport);
                    }
                }
            }
        }


        // Log published reports as they show up.
        private void runReportLoop() {
            long lastSeenReportTime = NO_REPORT_YET;

            while (true) {
                try {
                    reportReadySemaphore.acquire();
                } catch (InterruptedException e) {
                    break;
                }

                if (lastSeenReportTime != lastReportTime.get()) {
                    TelemetryReport report = lastReport.get();
                    long now = lastReportTime.get();

                    long minimumTransfer = Long.MAX_VALUE;
                    long maximumTransfer = Long.MIN_VALUE;
                    long totalTransfer = 0;

                    long minimumPending = Long.MAX_VALUE;
                    long maximumPending = Long.MIN_VALUE;
                    long totalPending = 0;

                    int validTimestepCount = 0;

                    for (int i = 0; i < timestepsPerReport; i++) {
                        if (report.observationSums[i] < 0) {
                            continue;
                        }

                        validTimestepCount += 1;

                        if (report.observationSums[i] < minimumTransfer) {
                            minimumTransfer = report.observationSums[i];
                        }

                        if (report.observationSums[i] > maximumTransfer) {
                            maximumTransfer = report.observationSums[i];
                        }

                        totalTransfer += report.observationSums[i];

                        if (report.readsPendingCounts[i] < minimumPending) {
                            minimumPending = report.readsPendingCounts[i];
                        }

                        if (report.readsPendingCounts[i] > maximumPending) {
                            maximumPending = report.readsPendingCounts[i];
                        }

                        totalPending += report.readsPendingCounts[i];
                    }

                    StringBuilder sb = new StringBuilder();

                    if (minimumTransfer != Long.MAX_VALUE) {
                        sb.append(String.format("minimum xfr=%.2f KB/s", (minimumTransfer / (timestepPeriod / 1000.0) / 1024.0)));
                    }
                    if (maximumTransfer != Long.MIN_VALUE) {
                        if (sb.length() > 0) { sb.append("; "); }
                        sb.append(String.format("maximum xfr=%.2f KB/s", (maximumTransfer / (timestepPeriod / 1000.0) / 1024.0)));
                    }
                    if (validTimestepCount > 0) {
                        if (sb.length() > 0) { sb.append("; "); }
                        sb.append(String.format("average xfr=%.2f KB/s", (totalTransfer / validTimestepCount / (timestepPeriod / 1000.0) / 1024.0)));
                    }

                    if (minimumPending != Long.MAX_VALUE) {
                        if (sb.length() > 0) { sb.append("; "); }
                        sb.append(String.format("minimum pending=%d", minimumPending));
                    }
                    if (maximumPending != Long.MIN_VALUE) {
                        if (sb.length() > 0) { sb.append("; "); }
                        sb.append(String.format("maximum pending=%d", maximumPending));
                    }
                    if (validTimestepCount > 0) {
                        if (sb.length() > 0) { sb.append("; "); }
                        sb.append(String.format("average pending=%.2f", ((float)totalPending / validTimestepCount)));
                    }

                    if (sb.length() > 0) {
                        sb.append("; reads_completed=" + report.readsCompletedCount);
                        System.err.println(now + " " + sb.toString());
                    }

                    lastSeenReportTime = now;
                }
            }
        }


        private static class TelemetryReport {
            public long[] observationSums;
            public long[] readsPendingCounts;
            public long readsCompletedCount;

            public TelemetryReport(int timestepsPerReport) {
                observationSums = new long[timestepsPerReport];
                readsPendingCounts = new long[timestepsPerReport];
            }
        }
    }
}
