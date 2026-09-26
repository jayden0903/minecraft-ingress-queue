package io.github.jayden0903.ingress;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** A routing receipt only. Identity is independently authenticated by Velocity. */
public final class EntryTicket {
    public record Ticket(UUID nonce, long issued, long expires, String username, String host) {}
    private final byte[] key;
    public EntryTicket(byte[] key) {
        if(key.length!=32)throw new IllegalArgumentException("Ticket key must be 32 bytes");
        this.key=key.clone();
    }
    private byte[] mac(byte[] data) throws GeneralSecurityException {
        Mac m=Mac.getInstance("HmacSHA256");m.init(new SecretKeySpec(key,"HmacSHA256"));return m.doFinal(data);
    }
    public byte[] issue(UUID nonce,String username,String host,long now) {
        try {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
            out.writeInt(1);out.writeLong(nonce.getMostSignificantBits());out.writeLong(nonce.getLeastSignificantBits());
            out.writeLong(now);out.writeLong(now+30000);out.writeUTF(username.toLowerCase(Locale.ROOT));out.writeUTF(host.toLowerCase(Locale.ROOT));out.flush();
            byte[] body=bytes.toByteArray();bytes.write(mac(body));return bytes.toByteArray();
        }catch(IOException|GeneralSecurityException e){throw new IllegalStateException(e);}
    }
    public Ticket verify(byte[] raw,String username,String host,long now) {
        try {
            if(raw==null||raw.length<70||raw.length>512)return null;
            byte[] body=Arrays.copyOf(raw,raw.length-32), signature=Arrays.copyOfRange(raw,raw.length-32,raw.length);
            if(!MessageDigest.isEqual(mac(body),signature))return null;
            DataInputStream in=new DataInputStream(new ByteArrayInputStream(body));
            if(in.readInt()!=1)return null;
            Ticket t=new Ticket(new UUID(in.readLong(),in.readLong()),in.readLong(),in.readLong(),in.readUTF(),in.readUTF());
            if(in.available()!=0||t.issued()>now+1000||t.expires()<=now||t.expires()-t.issued()!=30000)return null;
            return t.username().equals(username.toLowerCase(Locale.ROOT))&&t.host().equals(host.toLowerCase(Locale.ROOT))?t:null;
        }catch(IOException|GeneralSecurityException|RuntimeException invalid){return null;}
    }
}
