package io.github.jayden0903.fairqueue;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import java.util.*;
public class QueueEtaTest {
 static final long MIN=60_000L;
 static boolean near(Double v,double expected){return v!=null&&Math.abs(v-expected)<1e-9;}
 public static void main(String[] args){
  // Fewer than 3 samples: unknown, even after a long busy period.
  AdmissionRate r=new AdmissionRate();
  for(long t=0;t<=10*MIN;t+=1000)r.observe(t,true);
  assert r.admitted(5*MIN)&&r.admitted(10*MIN);
  assert r.perMinute(10*MIN)==null;
  assert AdmissionRate.etaSeconds(4,null)==null;
  // Admissions that happen while the queue is not seat-limited never count.
  AdmissionRate idle=new AdmissionRate();
  idle.observe(0,false);
  for(int i=0;i<5;i++)assert !idle.admitted(i*1000);
  assert idle.perMinute(5000)==null;
  // Steady rate: 10 busy minutes, one admission every 2 minutes -> 0.5 per minute.
  AdmissionRate s=new AdmissionRate();
  for(long t=0;t<=10*MIN;t+=1000){s.observe(t,true);if(t>0&&t%(2*MIN)==0)assert s.admitted(t);}
  assert near(s.perMinute(10*MIN),0.5):s.perMinute(10*MIN);
  assert AdmissionRate.etaSeconds(3,s.perMinute(10*MIN))==360;   // 3 / 0.5 * 60
  assert AdmissionRate.etaSeconds(1,1.5)==40 && AdmissionRate.etaSeconds(2,0.7)==172; // ceil(171.43)
  assert AdmissionRate.etaSeconds(0,null)==0 && AdmissionRate.etaSeconds(0,2.0)==0;
  // Idle periods (nobody waiting) are ignored: 4 busy minutes, 8 idle, 4 busy, 8 admissions -> 1 per minute.
  AdmissionRate g=new AdmissionRate();
  for(long t=0;t<=16*MIN;t+=1000){
   boolean busy=t<4*MIN||t>=12*MIN;g.observe(t,busy);
   if(busy&&t%MIN==30_000)assert g.admitted(t);
  }
  assert near(g.perMinute(16*MIN),8/((4*MIN-1000+4*MIN)/(double)MIN)):g.perMinute(16*MIN);
  assert Math.abs(g.perMinute(16*MIN)-1.0)<0.01;
  // Missed status ticks (>5 s gap) are not bridged into busy time.
  AdmissionRate gap=new AdmissionRate();
  gap.observe(0,true);assert !gap.admitted(10_000);gap.observe(10_000,true);assert gap.admitted(10_500);
  // A burst right after a short busy spell uses the 2-minute floor instead of reading 9 per minute.
  AdmissionRate b=new AdmissionRate();
  for(long t=0;t<=20_000;t+=1000)b.observe(t,true);
  for(int i=0;i<3;i++)assert b.admitted(20_000);
  assert near(b.perMinute(20_000),1.5):b.perMinute(20_000);
  // 20-minute window: old samples fall out and the rate becomes unknown again.
  for(long t=21_000;t<=22*MIN;t+=1000)b.observe(t,false);
  assert b.perMinute(22*MIN)==null;
  // Labels: whole minutes rounded up, under-a-minute, 30-minute cap, unknown.
  assert AdmissionRate.label(null).equals("예상 시간 계산 중");
  assert AdmissionRate.label(0L).equals("예상 1분 이내")&&AdmissionRate.label(59L).equals("예상 1분 이내");
  assert AdmissionRate.label(60L).equals("예상 약 1분")&&AdmissionRate.label(61L).equals("예상 약 2분");
  assert AdmissionRate.label(170L).equals("예상 약 3분")&&AdmissionRate.label(1800L).equals("예상 약 30분");
  assert AdmissionRate.label(1801L).equals("예상 30분 이상")&&AdmissionRate.label(86_400L).equals("예상 30분 이상");
  // newcomerSeconds: 0 with free seats and nobody waiting, null when unknown or the main backend is down.
  assert AdmissionRate.newcomerSeconds(true,0,59,60,1,null)==0;
  assert AdmissionRate.newcomerSeconds(true,0,59,60,1,0.5)==0;
  assert AdmissionRate.newcomerSeconds(true,0,60,60,1,null)==null;
  assert AdmissionRate.newcomerSeconds(true,0,60,60,1,2.0)==30;
  assert AdmissionRate.newcomerSeconds(true,9,60,60,10,2.0)==300;
  assert AdmissionRate.newcomerSeconds(false,0,10,60,1,2.0)==null;
  // Newcomer position follows the 3:1 priority merge used by position().
  AdmissionQueue q=new AdmissionQueue(1);UUID seated=UUID.randomUUID();assert q.initial(seated,Set.of(),true);q.admitted(seated);
  assert q.newcomerPosition()==1;
  List<UUID> normal=new ArrayList<>(),vip=new ArrayList<>();
  for(int i=0;i<2;i++){UUID id=UUID.randomUUID();normal.add(id);q.enqueue(id);}
  assert q.newcomerPosition()==3;
  Map<UUID,Long> prio=new HashMap<>();long until=System.currentTimeMillis()/1000+3600;
  for(int i=0;i<5;i++){UUID id=UUID.randomUUID();vip.add(id);prio.put(id,until);q.enqueue(id);}
  q.priorities(prio);
  // order: V V V N V V N [newcomer] -> position 8; the extra priority players never push a newcomer back past 3:1.
  assert q.newcomerPosition()==8:q.newcomerPosition();
  UUID probe=UUID.randomUUID();q.enqueue(probe);assert q.position(probe)==q.size()&&q.size()==8;
  q.departed(probe);
  // In-game subtitle text.
  String L="대기열";
  var plain=PlainTextComponentSerializer.plainText();
  assert plain.serialize(QueuePresentation.frame(L,3,60,60,5,true,false,170L).subtitle()).equals("예상 약 3분  ·  차례가 되면 자동으로 입장해요.");
  assert plain.serialize(QueuePresentation.frame(L,1,60,60,1,true,true,null).subtitle()).equals("예상 시간 계산 중  ·  차례가 되면 자동으로 입장해요.");
  assert plain.serialize(QueuePresentation.frame(L,1,60,60,1,true,false,40L).subtitle()).equals("예상 1분 이내  ·  차례가 되면 자동으로 입장해요.");
  assert plain.serialize(QueuePresentation.frame(L,2,0,60,2,false,false,40L).subtitle()).equals("서버가 준비되면 입장해요.");
  assert plain.serialize(QueuePresentation.frame(L,0,60,60,0,true,false,0L).subtitle()).equals("곧 서버로 이동해요.");
  assert plain.serialize(QueuePresentation.frame(L,4,60,60,4,true,false,170L).title()).equals("대기 4번");
  System.out.println("PASS: unknown <3 samples, steady 0.5/min, idle periods ignored, gap guard, burst floor, 20-min expiry, ETA ceil/labels/30-min cap, newcomerSeconds, 3:1 newcomer position, subtitle text");
 }
}
