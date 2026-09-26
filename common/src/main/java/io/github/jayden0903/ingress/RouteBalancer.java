package io.github.jayden0903.ingress;

import java.util.*;

/** All selection, reservation, acknowledgement and reload transitions share one short lock. No I/O here. */
public final class RouteBalancer {
    public record Target(String host, int port, boolean enabled) {
        public Target {
            host = host.toLowerCase(Locale.ROOT);
            if (!host.matches("[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?") || host.contains("..") || port < 1 || port > 65535)
                throw new IllegalArgumentException("Invalid target: " + host + ":" + port);
        }
    }
    public record Arrival(String host, String session) {}
    public record Reservation(UUID player, Target target, long expiresAt, String previousSession) {}
    public record Row(Target target, int online, int pending, long txBytes, long txWindowBytes, double txBytesPerSecond) {}
    public record Status(String mode, long ageMillis, List<Row> rows) {}
    private List<Target> targets = List.of();
    private Map<String,Integer> counts = Map.of();
    private Map<String,Long> traffic = Map.of();
    private Map<String,Long> recentTraffic = Map.of();
    private Map<String,Double> rates = Map.of();
    private long reservationBytes=262144;
    public synchronized void traffic(Map<String,Long> next,Map<String,Double> speed){
        traffic(next,Map.of(),speed);
    }
    public synchronized void traffic(Map<String,Long> totals,Map<String,Long> recent,Map<String,Double> speed){
        if(totals.values().stream().anyMatch(v->v==null||v<0)||recent.values().stream().anyMatch(v->v==null||v<0)||speed.values().stream().anyMatch(v->v==null||!Double.isFinite(v)||v<0))throw new IllegalArgumentException("Invalid traffic");
        traffic=Map.copyOf(totals);recentTraffic=Map.copyOf(recent);rates=Map.copyOf(speed);
    }
    private Map<UUID,Arrival> arrivals = Map.of();
    private final Map<UUID,Reservation> pending = new HashMap<>();
    private long lastGood, pendingNanos, staleNanos;
    private boolean received;
    private int cursor;

    public synchronized void configure(List<Target> next, long timeoutMillis, long staleMillis) {
        if (next.stream().noneMatch(Target::enabled)) throw new IllegalArgumentException("Enable at least one target");
        if (new HashSet<>(next.stream().map(Target::host).toList()).size() != next.size()) throw new IllegalArgumentException("Duplicate target hostname");
        if (timeoutMillis < 1000 || timeoutMillis > 120000 || staleMillis < 0 || staleMillis > 120000) throw new IllegalArgumentException("Invalid timeout");
        targets = List.copyOf(next); pendingNanos = timeoutMillis * 1_000_000L; staleNanos = staleMillis * 1_000_000L;
        cursor = Math.floorMod(cursor, targets.size());
    }
    public synchronized void accept(Map<String,Integer> next, Map<UUID,Arrival> present, long now) {
        if (next.values().stream().anyMatch(v -> v == null || v < 0 || v > 100000)) throw new IllegalArgumentException("Invalid count");
        counts = Map.copyOf(next); arrivals = Map.copyOf(present); received = true; lastGood = now;
        expire(now);
        pending.values().removeIf(r -> {
            Arrival a = arrivals.get(r.player());
            return a != null && a.host().equals(r.target().host()) && !a.session().equals(r.previousSession());
        });
    }
    public synchronized Reservation reserve(UUID player, long now) {
        expire(now);
        // A new connection for the same authenticated UUID supersedes its older reservation.
        pending.remove(player);
        boolean fresh = received && now - lastGood <= staleNanos;
        Map<String,Integer> loads = pendingCounts();
        int best = -1; long smallest = Long.MAX_VALUE;
        for (int n=0;n<targets.size();n++) {
            int i=(cursor+n)%targets.size(); Target t=targets.get(i); if (!t.enabled()) continue;
            long total=traffic.getOrDefault(t.host(),0L),reserved=reservationBytes*loads.getOrDefault(t.host(),0);
            long load=fresh ? total>Long.MAX_VALUE-reserved?Long.MAX_VALUE:total+reserved : 0;
            if (best<0 || load < smallest) { smallest=load;best=i; }
        }
        if (best < 0) throw new IllegalStateException("No enabled routes");
        Target target=targets.get(best);cursor=(best+1)%targets.size();
        Arrival old=arrivals.get(player);
        Reservation r=new Reservation(player,target,now+pendingNanos,old==null?null:old.session());pending.put(player,r);return r;
    }
    public synchronized void release(Reservation reservation) { pending.remove(reservation.player(),reservation); }
    public synchronized void expire(long now) { pending.values().removeIf(r -> now >= r.expiresAt()); }
    private Map<String,Integer> pendingCounts() {
        Map<String,Integer> result=new HashMap<>();pending.values().forEach(r->result.merge(r.target().host(),1,Integer::sum));return result;
    }
    public synchronized Status status(long now, long pollMillis) {
        expire(now);Map<String,Integer> loads=pendingCounts();long age=received?Math.max(0,(now-lastGood)/1_000_000):-1;
        String mode=!received || now-lastGood>staleNanos?"ROUND_ROBIN":age>Math.max(1000,pollMillis*2)?"LAST_GOOD":"LIVE";
        return new Status(mode,age,targets.stream().map(t->new Row(t,counts.getOrDefault(t.host(),0),loads.getOrDefault(t.host(),0),traffic.getOrDefault(t.host(),0L),recentTraffic.getOrDefault(t.host(),0L),rates.getOrDefault(t.host(),0.0))).toList());
    }
}
