package io.github.jayden0903.fairqueue;

import java.util.ArrayDeque;

/**
 * Measures real queue throughput and turns it into a waiting-time estimate (ETA).
 *
 * <p>Rate formula, over a sliding 20-minute window:
 * <pre>  perMinute = admissions / max(busyMinutes, 2)      (unknown while admissions &lt; 3)</pre>
 * <ul>
 * <li><b>busy</b> means seat-limited queueing: main backend healthy, at least one player waiting and every
 *   seat occupied or reserved. The 1 s status loop samples it ({@link #observe}). Idle time (nobody
 *   waiting), free-seat time and backend outages add nothing to the denominator, so a quiet night
 *   does not dilute the rate and the catch-up burst after an outage is not read as normal throughput.</li>
 * <li>An <b>admission</b> counts only when a player moves from the waiting lobby into the main backend while the
 *   queue is busy ({@link #admitted}). Instant admissions into free seats never count.</li>
 * <li>The 2-minute floor on the denominator stops a short burst (three players leaving together after
 *   20 s of queueing) from being read as 9 per minute. It can only lengthen early estimates, never
 *   shorten them, which is the safe direction for a promise shown to waiting players.</li>
 * </ul>
 * A windowed ratio was chosen over an EWMA: it has no smoothing constant to tune, forgets old data
 * exactly after 20 minutes, and can be checked by hand against the proxy log.
 *
 * <p>Timestamps are monotonic milliseconds supplied by the caller. The tracker has its own monitor and
 * does O(window) in-memory work only; it never blocks on IO.
 */
final class AdmissionRate {
    static final long WINDOW_MILLIS=20*60_000L, MIN_BUSY_MILLIS=2*60_000L;
    /** Observations arrive every second; a longer silence is a gap that must not count as busy time. */
    static final long GAP_MILLIS=5_000L;
    static final int MIN_SAMPLES=3, CAP_MINUTES=30;
    private final ArrayDeque<Long> admissions=new ArrayDeque<>();
    /** Busy intervals {start,end}, oldest first; the last one is still growing while {@link #open}. */
    private final ArrayDeque<long[]> busy=new ArrayDeque<>();
    private boolean open;
    private long lastObserved;

    private boolean busyAt(long now){return open&&now-lastObserved<=GAP_MILLIS;}

    /** Record the current queue state; called once per status tick. */
    synchronized void observe(long now,boolean seatLimited) {
        if(!busyAt(now))open=false;
        if(seatLimited){
            if(open)busy.peekLast()[1]=Math.max(busy.peekLast()[1],now);
            else{busy.addLast(new long[]{now,now});open=true;}
        } else open=false;
        lastObserved=now;
        prune(now);
    }

    /** A player waiting in the lobby reached the main backend. Returns whether it counted as a throughput sample. */
    synchronized boolean admitted(long now) {
        if(!busyAt(now))return false;
        busy.peekLast()[1]=Math.max(busy.peekLast()[1],now);
        admissions.addLast(now);
        prune(now);
        return true;
    }

    /** Admissions per minute of seat-limited queueing in the last 20 minutes, or null when unknown. */
    synchronized Double perMinute(long now) {
        prune(now);
        if(admissions.size()<MIN_SAMPLES)return null;
        long from=now-WINDOW_MILLIS,busyMillis=0;
        for(long[] b:busy)busyMillis+=Math.max(0L,Math.min(b[1],now)-Math.max(b[0],from));
        return admissions.size()*60_000d/Math.max(busyMillis,MIN_BUSY_MILLIS);
    }

    private void prune(long now) {
        long from=now-WINDOW_MILLIS;
        while(!admissions.isEmpty()&&admissions.peekFirst()<from)admissions.removeFirst();
        while(!busy.isEmpty()&&busy.peekFirst()[1]<from&&!(open&&busy.size()==1))busy.removeFirst();
    }

    /** ETA for a 1-based queue position: 0 when already admitting, null when the rate is unknown. */
    static Long etaSeconds(int position,Double perMinute) {
        if(position<=0)return 0L;
        if(perMinute==null||!(perMinute>0)||perMinute.isInfinite())return null;
        // ceil(p / rate * 60), evaluated as p*60/rate to avoid an extra rounding step (60/1.5 is exactly 40).
        return (long)Math.ceil(position*60d/perMinute);
    }

    /**
     * ETA for a brand-new normal player joining the back right now: 0 when seats are free and nobody
     * waits, null when unknown or while the main backend is unavailable (nothing can be admitted then).
     */
    static Long newcomerSeconds(boolean healthy,int waiting,int occupiedWithReservations,int capacity,int newcomerPosition,Double perMinute) {
        if(!healthy)return null;
        if(waiting==0&&occupiedWithReservations<capacity)return 0L;
        return etaSeconds(Math.max(1,newcomerPosition),perMinute);
    }

    /** Short Korean label for the waiting screen. */
    static String label(Long seconds) {
        if(seconds==null)return "예상 시간 계산 중";
        if(seconds<60)return "예상 1분 이내";
        long minutes=(seconds+59)/60;
        return minutes>CAP_MINUTES?"예상 "+CAP_MINUTES+"분 이상":"예상 약 "+minutes+"분";
    }
}
