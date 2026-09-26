package io.github.jayden0903.fairqueue;

import java.util.*;
import java.util.concurrent.*;
public final class AdmissionQueue100Test {
 public static void main(String[] args) throws Exception {
  AdmissionQueue q=new AdmissionQueue(100);List<UUID> ids=new ArrayList<>();for(int i=0;i<150;i++)ids.add(UUID.randomUUID());
  for(int i=0;i<150;i++)assert q.initial(ids.get(i),Set.of(),true)==(i<100);
  assert q.occupied(Set.of())==100 && q.pending()==100 && q.size()==50;
  for(int i=0;i<100;i++)q.admitted(ids.get(i));
  assert q.occupied(new HashSet<>(ids.subList(0,100)))==100;
  q.departed(ids.get(0));
  assert q.next(new HashSet<>(ids),Set.of(),true).orElseThrow().equals(ids.get(100));
  assert q.occupied(Set.of())==100;
  q.failed(ids.get(100));assert q.position(ids.get(100))==1;
  assert q.next(new HashSet<>(ids),Set.of(),false).isEmpty();
  assert q.next(Set.of(ids.get(101)),Set.of(),true).isEmpty(); // no overtaking a connecting head
  assert q.next(new HashSet<>(ids),Set.of(),true).orElseThrow().equals(ids.get(100));
  q.departed(ids.get(100));assert q.position(ids.get(101))==1;
  q.enqueue(ids.get(100));assert q.position(ids.get(100))==50;
  AdmissionQueue race=new AdmissionQueue(100);ExecutorService pool=Executors.newFixedThreadPool(16);
  List<Callable<Boolean>> tasks=new ArrayList<>();for(UUID id:ids)tasks.add(()->race.initial(id,Set.of(),true));
  long admitted=pool.invokeAll(tasks).stream().filter(f->{try{return f.get();}catch(Exception e){throw new RuntimeException(e);}}).count();pool.shutdown();
  assert admitted==100 && race.occupied(Set.of())==100 && race.size()==50;
  System.out.println("PASS: 100-seat cap, 101st queueing, reservation accounting, FIFO retry, disconnect cleanup, reconnect at tail, no overtaking, backend outage, 150 concurrent admissions");
 }
}
