package io.github.jayden0903.fairqueue;

import java.util.*;
public final class InstantAdmissionTest {
 public static void main(String[] args) {
  AdmissionQueue q=new AdmissionQueue(100);List<UUID> ids=new ArrayList<>();
  for(int i=0;i<50;i++){UUID id=UUID.randomUUID();assert q.initial(id,Set.of(),true);q.admitted(id);}
  for(int i=0;i<80;i++){UUID id=UUID.randomUUID();ids.add(id);q.enqueue(id);}
  Set<UUID> eligible=new HashSet<>(ids);
  for(int i=0;i<50;i++)assert q.next(eligible,Set.of(),true).orElseThrow().equals(ids.get(i));
  assert q.next(eligible,Set.of(),true).isEmpty();
  assert q.occupied(Set.of())==100&&q.pending()==50&&q.size()==30;
  System.out.println("PASS: 50 free seats reserved in one pass, FIFO preserved, capacity100 with30 left waiting");
 }
}
