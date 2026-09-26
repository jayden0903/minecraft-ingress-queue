package io.github.jayden0903.fairqueue;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
public class PresenceMessageTest {
 public static void main(String[] args){
  var join=PresenceMessage.create("tester",false,true);var leave=PresenceMessage.create("tester",true,false);
  assert PlainTextComponentSerializer.plainText().serialize(join).equals("[+] [USER] tester");
  assert PlainTextComponentSerializer.plainText().serialize(leave).equals("[-] [ADMIN] tester");
  assert join.children().get(0).color().value()==0x85D6A0;
  assert leave.children().get(0).color().value()==0xEF8585;
  assert join.children().get(2).color().value()==0xFFC99E;
  assert leave.children().get(2).color().value()==0xFF8F88;
  System.out.println("PASS presence text and green/red/role palette");
 }
}
