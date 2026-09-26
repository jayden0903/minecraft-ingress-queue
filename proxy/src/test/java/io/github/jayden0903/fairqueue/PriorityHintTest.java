package io.github.jayden0903.fairqueue;
import com.google.gson.JsonParser;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;
public class PriorityHintTest {
 static final long UNTIL=System.currentTimeMillis()/1000+3600;
 /** Enqueues a new priority probe and checks the real 3:1 order puts it exactly where priorityPosition() said. */
 static int crossCheck(AdmissionQueue q,Map<UUID,Long> prio){
  int predicted=q.priorityPosition();
  UUID probe=UUID.randomUUID();var with=new HashMap<>(prio);with.put(probe,UNTIL);q.priorities(with);
  q.enqueue(probe);int actual=q.position(probe);q.departed(probe);q.priorities(prio);
  assert actual==predicted:"predicted "+predicted+" actual "+actual;
  return predicted;
 }
 static Player player(UUID id,List<Component> sink){
  return (Player)Proxy.newProxyInstance(Player.class.getClassLoader(),new Class<?>[]{Player.class},(self,m,args)->{
   if(m.getName().equals("getUniqueId"))return id;
   if(m.getName().equals("sendMessage")&&args!=null&&args[0] instanceof Component c){sink.add(c);return null;}
   if(m.getName().equals("hashCode"))return System.identityHashCode(self);
   if(m.getName().equals("equals"))return self==args[0];
   throw new UnsupportedOperationException(m.getName());
  });
 }
 public static void main(String[] args) throws Exception {
  var plain=PlainTextComponentSerializer.plainText();
  // --- priorityPosition, pure form (high waiting, normal waiting, current streak) ---
  assert AdmissionQueue.priorityPosition(0,0,0)==1;          // empty queue
  assert AdmissionQueue.priorityPosition(0,5,0)==1;          // only normal players: straight to the front
  assert AdmissionQueue.priorityPosition(4,0,0)==5;          // only priority players: back of the priority lane
  assert AdmissionQueue.priorityPosition(4,0,3)==5;          // streak cannot block priority players when nobody else waits
  assert AdmissionQueue.priorityPosition(2,3,0)==3;          // H H [S]
  assert AdmissionQueue.priorityPosition(3,3,0)==5;          // H H H N [S]
  assert AdmissionQueue.priorityPosition(5,2,0)==7;          // H H H N H H [S]
  assert AdmissionQueue.priorityPosition(7,1,0)==9;          // H H H N H H H H [S]
  assert AdmissionQueue.priorityPosition(0,2,3)==2;          // streak used up: one normal player first
  assert AdmissionQueue.priorityPosition(1,2,3)==3;          // N H [S]
  assert AdmissionQueue.priorityPosition(6,9,1)==9;          // H H N H H H N H [S]
  // --- instance form, cross-checked against the real ordering for many arrival mixes ---
  AdmissionQueue empty=new AdmissionQueue(1);UUID seat=UUID.randomUUID();assert empty.initial(seat,Set.of(),true);empty.admitted(seat);
  assert crossCheck(empty,Map.of())==1;
  Random rnd=new Random(12345);
  for(int round=0;round<200;round++){
   AdmissionQueue q=new AdmissionQueue(1);UUID s=UUID.randomUUID();
   Map<UUID,Long> prio=new HashMap<>();
   // Some rounds seat a priority player first so the streak starts at 1.
   if(rnd.nextBoolean())prio.put(s,UNTIL);
   q.priorities(prio);assert q.initial(s,Set.of(),true);q.admitted(s);
   int n=rnd.nextInt(25),high=0,normal=0;
   for(int i=0;i<n;i++){UUID id=UUID.randomUUID();if(rnd.nextInt(3)==0){prio.put(id,UNTIL);high++;}else normal++;q.priorities(prio);q.enqueue(id);}
   int predicted=crossCheck(q,prio);
   if(normal==0)assert predicted==high+1;
   if(high==0&&normal>0)assert predicted==1;
  }
  // Only priority players waiting, and only normal players waiting, through the real queue.
  AdmissionQueue onlyVip=new AdmissionQueue(1);UUID a=UUID.randomUUID();assert onlyVip.initial(a,Set.of(),true);onlyVip.admitted(a);
  Map<UUID,Long> vip=new HashMap<>();for(int i=0;i<6;i++){UUID id=UUID.randomUUID();vip.put(id,UNTIL);onlyVip.priorities(vip);onlyVip.enqueue(id);}
  assert crossCheck(onlyVip,vip)==7;
  AdmissionQueue onlyNormal=new AdmissionQueue(1);UUID b=UUID.randomUUID();assert onlyNormal.initial(b,Set.of(),true);onlyNormal.admitted(b);
  List<UUID> normals=new ArrayList<>();for(int i=0;i<8;i++){UUID id=UUID.randomUUID();normals.add(id);onlyNormal.enqueue(id);}
  assert crossCheck(onlyNormal,Map.of())==1;
  // A seated priority streak of 3 lets the head normal player go first.
  AdmissionQueue streak=new AdmissionQueue(3);Map<UUID,Long> sp=new HashMap<>();List<UUID> seated=new ArrayList<>();
  for(int i=0;i<3;i++){UUID id=UUID.randomUUID();sp.put(id,UNTIL);seated.add(id);}streak.priorities(sp);
  for(UUID id:seated){assert streak.initial(id,Set.of(),true);streak.admitted(id);}
  UUID n1=UUID.randomUUID(),n2=UUID.randomUUID();streak.enqueue(n1);streak.enqueue(n2);
  assert crossCheck(streak,sp)==2;
  // Expired priority entries count as normal players, like preferred().
  AdmissionQueue expired=new AdmissionQueue(1);UUID c=UUID.randomUUID();assert expired.initial(c,Set.of(),true);expired.admitted(c);
  UUID old=UUID.randomUUID();Map<UUID,Long> ep=Map.of(old,System.currentTimeMillis()/1000-10);expired.priorities(ep);expired.enqueue(old);
  assert crossCheck(expired,ep)==1;
  // --- hint condition ---
  assert QueuePresentation.priorityHint(false,true,5,2);        // exactly 3 places ahead
  assert !QueuePresentation.priorityHint(false,true,4,2);       // only 2 places: stay quiet
  assert QueuePresentation.priorityHint(false,true,4,1);
  assert !QueuePresentation.priorityHint(true,true,20,2);       // never to priority players
  assert !QueuePresentation.priorityHint(false,false,20,2);     // never while the main backend is unhealthy
  assert !QueuePresentation.priorityHint(false,true,0,1);       // being admitted
  assert !QueuePresentation.priorityHint(false,true,1,1);       // nobody ahead
  assert !QueuePresentation.priorityHint(false,true,-1,1);      // not waiting
  // Through the real queue: 6 normal players, no priority players -> q=1; 4th in line sees it, 3rd does not.
  int q1=onlyNormal.priorityPosition();assert q1==1;
  assert QueuePresentation.priorityHint(false,true,onlyNormal.position(normals.get(3)),q1);
  assert !QueuePresentation.priorityHint(false,true,onlyNormal.position(normals.get(2)),q1);
  // Six priority players ahead of everyone: a new one would be 7th, the 8 normal players behind gain little.
  assert !QueuePresentation.priorityHint(false,true,2,AdmissionQueue.priorityPosition(6,8,0));
  // --- throttle: first time, then at most every 3 minutes ---
  assert QueuePresentation.hintDue(null,0);
  assert !QueuePresentation.hintDue(1000L,1000L+179_999);
  assert QueuePresentation.hintDue(1000L,1000L+180_000);
  // --- line text and palette ---
  Component line=QueuePresentation.hintLine("대기열",2,"후원 안내는 공지를 확인해 주세요");
  assert plain.serialize(line).equals(" 대기열 │ 우선 입장 대상은 지금 들어와도 2번째예요 · 후원 안내는 공지를 확인해 주세요"):plain.serialize(line);
  assert !plain.serialize(line).codePoints().anyMatch(cp->cp==0x2726);
  assert line.color().value()==0xFFC99E && line.children().get(0).color().value()==0xFFF3DF && line.children().get(1).color().value()==0xC8B49A;
  // --- per-player state: sent on entry, suppressed within 3 min, again after, not when ineligible, reset on disconnect ---
  assert plain.serialize(QueuePresentation.hintLine("대기열",4,"")).equals(" 대기열 │ 우선 입장 대상은 지금 들어와도 4번째예요");
  QueuePresentation view=new QueuePresentation("대기열","");List<Component> sent=new ArrayList<>();UUID pid=UUID.randomUUID();Player p=player(pid,sent);
  view.hint(p,false,2,0);assert sent.isEmpty();
  view.hint(p,true,2,0);assert sent.size()==1;
  for(long t=1000;t<180_000;t+=1000)view.hint(p,true,2,t);
  assert sent.size()==1;
  view.hint(p,true,3,180_000);assert sent.size()==2&&plain.serialize(sent.get(1)).contains("3번째");
  view.forget(pid);view.hint(p,true,2,180_500);assert sent.size()==3;
  // --- gradient parsing ---
  var g=PrioritySnapshot.gradient(JsonParser.parseString("[\"FF0000\",\"0000ff\"]"));
  assert g!=null&&g.from().value()==0xFF0000&&g.to().value()==0x0000FF;
  assert PrioritySnapshot.gradient(JsonParser.parseString("[\"#FFC99E\",\"#C5B5EF\"]")).to().value()==0xC5B5EF;
  assert PrioritySnapshot.gradient(null)==null;
  assert PrioritySnapshot.gradient(JsonParser.parseString("null"))==null;
  assert PrioritySnapshot.gradient(JsonParser.parseString("[\"FF0000\"]"))==null;
  assert PrioritySnapshot.gradient(JsonParser.parseString("[\"FF0000\",\"00FF00\",\"0000FF\"]"))==null;
  assert PrioritySnapshot.gradient(JsonParser.parseString("[\"GG0000\",\"0000FF\"]"))==null;
  assert PrioritySnapshot.gradient(JsonParser.parseString("[\"FF000\",\"0000FF\"]"))==null;
  assert PrioritySnapshot.gradient(JsonParser.parseString("[\"FF0000\",255]"))==null;
  assert PrioritySnapshot.gradient(JsonParser.parseString("[\"FF0000\",null]"))==null;
  assert PrioritySnapshot.gradient(JsonParser.parseString("\"FF0000,0000FF\""))==null;
  assert PrioritySnapshot.gradient(JsonParser.parseString("{\"from\":\"FF0000\"}"))==null;
  // A bad gradient never rejects the snapshot; other members and fields load normally.
  Path dir=Files.createTempDirectory("priority-hint-test");Path file=dir.resolve("priority.json");
  UUID good=UUID.randomUUID(),bad=UUID.randomUUID(),none=UUID.randomUUID();
  Files.writeString(file,"{\"version\":1,\"members\":{"
   +"\""+good+"\":{\"expires\":"+UNTIL+",\"name\":\"a\",\"title\":\"t\",\"color\":\"FFC99E\",\"joining\":\"인사\",\"theme\":\"FFC99E\",\"badge\":\"b\",\"gradient\":[\"FFC99E\",\"C5B5EF\"]},"
   +"\""+bad+"\":{\"expires\":"+UNTIL+",\"name\":\"b\",\"title\":\"t\",\"color\":\"B8DDA8\",\"joining\":\"반가워요\",\"theme\":\"FFC99E\",\"badge\":\"b\",\"gradient\":[\"nothex\",42]},"
   +"\""+none+"\":{\"expires\":"+UNTIL+",\"name\":\"c\",\"title\":\"t\",\"color\":\"C5B5EF\",\"joining\":\"기본\",\"theme\":\"FFC99E\",\"badge\":\"b\"}}}");
  PrioritySnapshot snap=new PrioritySnapshot();snap.read(file);
  assert snap.active(good).gradient().from().value()==0xFFC99E&&snap.active(good).gradient().to().value()==0xC5B5EF;
  assert snap.active(bad)!=null&&snap.active(bad).gradient()==null&&snap.active(bad).color().equals("B8DDA8");
  assert snap.active(none)!=null&&snap.active(none).gradient()==null;
  assert snap.priorities().size()==3;
  Files.delete(file);Files.delete(dir);
  // --- gradient rendering: one Component per code point, exact end colours ---
  var fromTo=new PrioritySnapshot.Gradient(TextColor.color(0xFF0000),TextColor.color(0x0000FF));
  Component name=PresenceMessage.name("Steve",TextColor.color(0x00FF00),fromTo);
  assert plain.serialize(name).equals("Steve")&&name.children().size()==5;
  assert name.children().get(0).color().value()==0xFF0000&&name.children().get(4).color().value()==0x0000FF;
  for(Component ch:name.children())assert ((TextComponent)ch).content().codePointCount(0,((TextComponent)ch).content().length())==1;
  Component mid=PresenceMessage.name("abc",TextColor.color(0x00FF00),fromTo);
  int m=mid.children().get(1).color().value();assert (m>>16)>=127&&(m>>16)<=128&&(m&0xFF)>=127&&(m&0xFF)<=128&&((m>>8)&0xFF)==0:Integer.toHexString(m);
  Component one=PresenceMessage.name("A",TextColor.color(0x00FF00),fromTo);
  assert plain.serialize(one).equals("A")&&one.children().size()==1&&one.children().get(0).color().value()==0xFF0000;
  Component emoji=PresenceMessage.name("a😀b",TextColor.color(0x00FF00),fromTo);
  assert emoji.children().size()==3&&((TextComponent)emoji.children().get(1)).content().equals("😀")&&emoji.children().get(2).color().value()==0x0000FF;
  Component solid=PresenceMessage.name("Steve",TextColor.color(0x00FF00),null);
  assert solid.children().isEmpty()&&solid.color().value()==0x00FF00&&plain.serialize(solid).equals("Steve");
  System.out.println("PASS: priorityPosition pure cases + 200 random mixes cross-checked against real 3:1 order (empty, only priority, only normal, streak, expired), hint condition, 3-minute throttle + forget, hint text/palette, tolerant gradient parsing + snapshot survives bad gradient, per-code-point gradient rendering (ends, 1-char, surrogate pair, null)");
 }
}
