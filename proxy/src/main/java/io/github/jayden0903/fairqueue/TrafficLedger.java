package io.github.jayden0903.fairqueue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Monotonic cumulative bases, separate from process-local rolling windows. */
public final class TrafficLedger {
    private final Path file;
    private final Map<String,Long> base;
    private Map<String,Long> saved;
    public TrafficLedger(Path file) throws IOException {
        this.file=file.toAbsolutePath();
        Path backup=backup();
        Map<String,Long> a=null,b=null;
        IOException failure=null;
        if(Files.exists(this.file))try{a=read(this.file);}catch(IOException e){failure=e;}
        if(Files.exists(backup))try{b=read(backup);}catch(IOException e){failure=e;}
        if(a==null&&b==null&&failure!=null)throw new IOException("Both traffic checkpoints invalid; refusing to reset totals",failure);
        var loaded=new TreeMap<String,Long>();
        if(a!=null)loaded.putAll(a);
        if(b!=null)b.forEach((h,n)->loaded.merge(h,n,Math::max));
        base=Map.copyOf(loaded);
        // Rewrite if either copy is missing, damaged or older.
        saved=a!=null&&a.equals(b)?base:null;
    }
    private Path backup(){return file.resolveSibling(file.getFileName()+".bak");}
    public Set<String> hosts(){return base.keySet();}
    public long total(String host,long sessionBytes){
        if(sessionBytes<0)throw new IllegalArgumentException("Negative traffic");
        return Math.addExact(base.getOrDefault(host,0L),sessionBytes);
    }
    public synchronized void save(Map<String,Long> session) throws IOException {
        var cumulative=new TreeMap<>(base);
        session.forEach((h,n)->cumulative.put(h,total(h,n)));
        if(cumulative.equals(saved))return;
        byte[] payload=encode(cumulative);
        Files.createDirectories(file.getParent());
        // Independent checksummed copies. On recovery choose the greater valid count per route.
        atomicWrite(backup(),payload);
        atomicWrite(file,payload);
        try(FileChannel dir=FileChannel.open(file.getParent(),StandardOpenOption.READ)){dir.force(true);}
        saved=Map.copyOf(cumulative);
    }
    private static void atomicWrite(Path destination,byte[] bytes) throws IOException {
        Path tmp=destination.resolveSibling(destination.getFileName()+".tmp");
        try(FileChannel out=FileChannel.open(tmp,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)){
            ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())out.write(buffer);out.force(true);
        }
        Files.move(tmp,destination,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
    }
    private static String payload(Map<String,Long> totals) throws IOException {
        StringBuilder out=new StringBuilder("schema=1\n");
        for(var e:new TreeMap<>(totals).entrySet()){
            if(!e.getKey().matches("[a-z0-9][a-z0-9.-]{0,252}")||e.getValue()<0)throw new IOException("Invalid traffic entry");
            out.append("total.").append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        return out.toString();
    }
    private static String hash(String text){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    static byte[] encode(Map<String,Long> totals) throws IOException {
        String body=payload(totals);return (body+"sha256="+hash(body)+"\n").getBytes(StandardCharsets.UTF_8);
    }
    static Map<String,Long> read(Path path) throws IOException {
        if(Files.size(path)>1_048_576)throw new IOException("Oversized traffic checkpoint");
        var p=new Properties();
        try(var in=Files.newBufferedReader(path,StandardCharsets.UTF_8)){p.load(in);}
        catch(IllegalArgumentException e){throw new IOException("Invalid checkpoint syntax",e);}
        if(!"1".equals(p.getProperty("schema")))throw new IOException("Invalid checkpoint version");
        var result=new TreeMap<String,Long>();
        try{
            for(String key:p.stringPropertyNames()){
                if(key.startsWith("total."))result.put(key.substring(6),Long.parseLong(p.getProperty(key)));
                else if(!key.equals("schema")&&!key.equals("sha256"))throw new IOException("Unknown checkpoint entry");
            }
        }catch(NumberFormatException e){throw new IOException("Invalid traffic count",e);}
        if(!hash(payload(result)).equals(p.getProperty("sha256")))throw new IOException("Traffic checkpoint checksum mismatch");
        return result;
    }
}
