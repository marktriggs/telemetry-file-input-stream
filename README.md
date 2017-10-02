A FileInputStream that logs throughput (KB/s) of reads.

(Nothing really file-specific about most of it, actually--could easily be
adapted for an InputStream instead)

Makes reasonable attempts to be robust and low-overhead.  Here's the plan:

  * Each TelemetryFileInputStream instance logs the size of each read to a
    shared Telemetry instance.  Right now that means taking a mutex, but it
    isn't held for long.  The observation is written to a circular buffer and
    we cross our fingers that we don't run out of space.

  * An aggregation thread wakes up periodically (1 second by default), grabs
    the mutex and tallies up the observations seen in the last second.  The
    aggregated "timestep" total is written to a second array, and the
    circular buffer is cleared.

  * When we have accumulated enough timestep values to cover a report period,
    we publish a copy of the array (via AtomicReference).  The goal here is
    to decouple the (critical path) aggregation process from IO.

  * A third reporting thread wakes up periodically and checks to see whether a
    new report has been published.  If so, it logs it.

If the circular buffer fills up, it just wraps around but shouldn't cause
problems.  A warning is logged if that happens and the affected stats are
skipped.

If logging stalls for some reason, it shouldn't matter: the aggregation
thread will just publish reports that nobody ever reads.  Some people make
good careers doing that.

