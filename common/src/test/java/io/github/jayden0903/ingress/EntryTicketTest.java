package io.github.jayden0903.ingress;
import io.github.jayden0903.ingress.EntryTicket;
import java.util.*;
public class EntryTicketTest {
 public static void main(String[] args) {
  byte[] key=new byte[32];new java.security.SecureRandom().nextBytes(key);EntryTicket c=new EntryTicket(key);
  UUID id=UUID.randomUUID();long now=100000;
  byte[] b=c.issue(id,"PlayerA","s1.example.com",now);
  assert c.verify(b,"playera","s1.example.com",now+1000).nonce().equals(id);
  assert c.verify(b,"PlayerB","s1.example.com",now+1000)==null;
  assert c.verify(b,"PlayerA","s2.example.com",now+1000)==null;
  assert c.verify(b,"PlayerA","s1.example.com",now+30000)==null;
  assert c.verify(b,"PlayerA","s1.example.com",now-2000)==null;
  b[20]^=1;assert c.verify(b,"PlayerA","s1.example.com",now+1000)==null;
  assert c.verify(null,"PlayerA","s1.example.com",now)==null;
  System.out.println("PASS: signed ticket, name/host binding, expiry, future timestamp, invalid signature, missing ticket");
 }
}
