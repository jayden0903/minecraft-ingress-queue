package io.github.jayden0903.fairqueue;

import java.util.*;

/** One lock owns fair priority/FIFO order and in-flight seats. A pending connection consumes a seat. */
public final class AdmissionQueue {
    private final int capacity;
    private Map<UUID,Long> priority=Map.of();
    private int priorityStreak;
    public synchronized void priorities(Map<UUID,Long> value){priority=Map.copyOf(value);}
    private boolean preferred(UUID id){return priority.getOrDefault(id,0L)>System.currentTimeMillis()/1000;}
    private final LinkedHashSet<UUID> waiting = new LinkedHashSet<>();
    private final Map<UUID, Long> reservations = new HashMap<>();
    private final Set<UUID> active = new HashSet<>();

    public AdmissionQueue(int capacity) {
        if (capacity < 1 || capacity > 200) throw new IllegalArgumentException("capacity must be 1..200");
        this.capacity = capacity;
    }
    public synchronized void enqueue(UUID id) {
        if (!active.contains(id)) waiting.add(id);
    }
    public synchronized boolean initial(UUID id, Set<UUID> actual, boolean healthy) {
        enqueue(id);
        return healthy && firstWaiting().filter(id::equals).isPresent() && reserve(id, actual);
    }
    public synchronized Optional<UUID> next(Set<UUID> eligible, Set<UUID> actual, boolean healthy) {
        if (!healthy) return Optional.empty();
        Optional<UUID> first = firstWaiting();
        if (first.isPresent() && eligible.contains(first.get()) && reserve(first.get(), actual)) return first;
        return Optional.empty();
    }
    private Optional<UUID> firstWaiting() {
        return ordered().stream().findFirst();
    }
    private boolean reserve(UUID id, Set<UUID> actual) {
        if (reservations.containsKey(id) || occupied(actual) >= capacity) return false;
        long now=System.nanoTime();
        reservations.put(id, now);
        priorityStreak=preferred(id)?Math.min(3,priorityStreak+1):0;
        return true;
    }
    public synchronized int occupied(Set<UUID> actual) {
        Set<UUID> all = new HashSet<>(actual);
        all.addAll(active);
        all.addAll(reservations.keySet());
        return all.size();
    }
    public synchronized void admitted(UUID id) {
        waiting.remove(id);
        reservations.remove(id);
        active.add(id);
    }
    public synchronized void queued(UUID id) {
        active.remove(id);
        waiting.add(id);
    }
    public synchronized void failed(UUID id) { reservations.remove(id); }
    public synchronized void departed(UUID id) {
        waiting.remove(id);
        reservations.remove(id);
        active.remove(id);
    }
    public synchronized boolean reserved(UUID id) { return reservations.containsKey(id); }
    private List<UUID> ordered() {
        var high=new ArrayDeque<UUID>();var normal=new ArrayDeque<UUID>();
        for(UUID id:waiting)if(!reservations.containsKey(id)){if(preferred(id))high.add(id);else normal.add(id);}
        var result=new ArrayList<UUID>();int streak=priorityStreak;
        while(!high.isEmpty()||!normal.isEmpty()){
            if(!high.isEmpty()&&(normal.isEmpty()||streak<3)){result.add(high.remove());streak=Math.min(3,streak+1);}
            else{result.add(normal.remove());streak=0;}
        }
        return result;
    }
    public synchronized int position(UUID id) {
        if(reservations.containsKey(id))return 0;
        int i=ordered().indexOf(id);return i<0?-1:i+1;
    }
    /** 1-based position a new normal player would get at the back right now, using the same 3:1 merge as ordered(). */
    public synchronized int newcomerPosition() {
        int high=0,normal=1,streak=priorityStreak,position=0;
        for(UUID id:waiting)if(!reservations.containsKey(id)){if(preferred(id))high++;else normal++;}
        while(normal>0){
            position++;
            if(high>0&&streak<3){high--;streak=Math.min(3,streak+1);}
            else{normal--;streak=0;}
        }
        return position;
    }
    /**
     * 1-based position a brand-new priority player would get if they joined right now: the back of the
     * priority lane, merged with the same 3:1 rule and current streak as ordered(). 1 when nobody waits.
     */
    public synchronized int priorityPosition() {
        int high=0,normal=0;
        for(UUID id:waiting)if(!reservations.containsKey(id)){if(preferred(id))high++;else normal++;}
        return priorityPosition(high,normal,priorityStreak);
    }
    /** Pure form of {@link #priorityPosition()}: priority/normal players already waiting, current streak. */
    static int priorityPosition(int high,int normal,int streak) {
        high++; // the hypothetical newcomer joins the back of the priority lane
        int position=0;
        while(high>0){
            position++;
            if(normal==0||streak<3){high--;streak=Math.min(3,streak+1);}
            else{normal--;streak=0;}
        }
        return position;
    }
    public synchronized int size() { return (int) waiting.stream().filter(id -> !reservations.containsKey(id)).count(); }
    public synchronized int pending() { return reservations.size(); }
    public synchronized List<UUID> expired(long ageNanos) {
        long now = System.nanoTime();
        return reservations.entrySet().stream().filter(e -> now - e.getValue() > ageNanos).map(Map.Entry::getKey).toList();
    }
}
