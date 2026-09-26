package io.github.jayden0903.fairqueue;

import java.util.*;
import java.util.concurrent.*;
public final class AdmissionQueueTest {
 public static void main(String[] args) throws Exception {
  AdmissionQueue q=new AdmissionQueue(200);List<UUID> ids=new ArrayList<>();for(int i=0;i<250;i++)ids.add(UUID.randomUUID());
  for(int i=0;i<250;i++)assert q.initial(ids.get(i),Set.of(),true)==(i<200);
  assert q.occupied(Set.of())==200 && q.pending()==200 && q.size()==50;
  for(int i=0;i<200;i++)q.admitted(ids.get(i));
  assert q.occupied(new HashSet<>(ids.subList(0,200)))==200;
  q.departed(ids.get(0));
  assert q.next(new HashSet<>(ids),Set.of(),true).orElseThrow().equals(ids.get(200));
  assert q.occupied(Set.of())==200;
  q.failed(ids.get(200));assert q.position(ids.get(200))==1;
  assert q.next(new HashSet<>(ids),Set.of(),false).isEmpty();
  assert q.next(Set.of(ids.get(201)),Set.of(),true).isEmpty(); // no overtaking a connecting head
  assert q.next(new HashSet<>(ids),Set.of(),true).orElseThrow().equals(ids.get(200));
  q.departed(ids.get(200));assert q.position(ids.get(201))==1;
  q.enqueue(ids.get(200));assert q.position(ids.get(200))==50;
  AdmissionQueue race=new AdmissionQueue(200);ExecutorService pool=Executors.newFixedThreadPool(16);
  List<Callable<Boolean>> tasks=new ArrayList<>();for(UUID id:ids)tasks.add(()->race.initial(id,Set.of(),true));
  long admitted=pool.invokeAll(tasks).stream().filter(f->{try{return f.get();}catch(Exception e){throw new RuntimeException(e);}}).count();pool.shutdown();
  assert admitted==200 && race.occupied(Set.of())==200 && race.size()==50;
  AdmissionQueue launch=new AdmissionQueue(100);
  ExecutorService joins=Executors.newFixedThreadPool(16);
  List<Callable<Boolean>> burst=new ArrayList<>();
  for(UUID id:ids)burst.add(()->launch.initial(id,Set.of(),true));
  long granted=joins.invokeAll(burst).stream().filter(f->{try{return f.get();}catch(Exception e){throw new RuntimeException(e);}}).count();joins.shutdown();
  assert granted==100 && launch.pending()==100 && launch.size()==150;
  List<UUID> first=ids.stream().filter(launch::reserved).toList();
  first.forEach(launch::admitted);
  assert launch.occupied(new HashSet<>(first))==100;
  assert launch.next(new HashSet<>(ids),new HashSet<>(first),true).isEmpty();
  UUID departed=first.get(0);launch.departed(departed);
  Set<UUID> online=new HashSet<>(first);online.remove(departed);
  assert launch.next(new HashSet<>(ids),online,true).isPresent();
  assert launch.occupied(online)==100;
  System.out.println("PASS: 100-seat cap, 250 concurrent arrivals, 101st waits, one departure releases one seat");
  System.out.println("PASS: 200-seat cap, 201st queueing, reservation accounting, FIFO retry, disconnect cleanup, reconnect at tail, no overtaking, backend outage, 250 concurrent admissions");
 }
}
