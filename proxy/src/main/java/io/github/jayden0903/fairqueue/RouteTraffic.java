package io.github.jayden0903.fairqueue;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;

/** Counts successful, encoded downstream writes, after compression/encryption.
 * TCP/IP headers, retransmissions and shield edge-to-client bytes are not included. */
public final class RouteTraffic implements AutoCloseable {
    private record Sample(long at,long bytes) {}
    private final ConcurrentHashMap<String,LongAdder> totals=new ConcurrentHashMap<>();
    private final Map<String,ArrayDeque<Sample>> windows=new HashMap<>();
    private final Set<Player> measured=ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService sampler=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"FairQueue-Traffic");t.setDaemon(true);return t;});
    private volatile Map<String,Object> snapshot=Map.of();
    private final TrafficLedger ledger;
    private final org.slf4j.Logger log;
    private long lastSaveWarning;
    private volatile boolean closed;
    /** Optional replacement for the server brand shown in the client F3 screen; null leaves it untouched. */
    private final String serverBrand;
    public RouteTraffic(List<String> hosts,java.nio.file.Path file,org.slf4j.Logger log) throws java.io.IOException {this(hosts,file,log,null);}
    public RouteTraffic(List<String> hosts,java.nio.file.Path file,org.slf4j.Logger log,String serverBrand) throws java.io.IOException {
        this.log=log;this.serverBrand=serverBrand;ledger=new TrafficLedger(file);
        ledger.hosts().forEach(h->totals.put(h,new LongAdder()));
        hosts.forEach(h->totals.put(h,new LongAdder()));
        sample();persist();
        sampler.scheduleAtFixedRate(()->{try{sample();persist();}catch(Exception e){
            long now=System.nanoTime();if(now-lastSaveWarning>30_000_000_000L){lastSaveWarning=now;log.error("Traffic checkpoint failed; in-memory totals retained",e);}
        }},1,1,TimeUnit.SECONDS);
    }
    private synchronized void persist() throws java.io.IOException {
        var counts=new TreeMap<String,Long>();totals.forEach((h,n)->counts.put(h,n.sum()));ledger.save(counts);
    }
    public void attach(Player p,String host){
        if(closed)throw new IllegalStateException("Traffic tracker closed");
        if(!(p instanceof ConnectedPlayer cp))throw new IllegalStateException("Unsupported Velocity connection");
        var counter=totals.computeIfAbsent(host,h->new LongAdder());var channel=cp.getConnection().getChannel();
        channel.eventLoop().execute(()->{if(!channel.isActive())return;
            if(serverBrand!=null)channel.pipeline().addLast("fairqueue-server-brand",new ServerBrand(serverBrand));
            channel.pipeline().addFirst("fairqueue-route-tx",new ChannelOutboundHandlerAdapter(){
                @Override public void write(ChannelHandlerContext ctx,Object msg,ChannelPromise promise)throws Exception{
                    int bytes=msg instanceof ByteBuf b?b.readableBytes():0;
                    if(bytes>0){ChannelPromise observed=promise.unvoid();observed.addListener(f->{if(f.isSuccess())counter.add(bytes);});ctx.write(msg,observed);}else ctx.write(msg,promise);
                }
            });measured.add(p);
        });
    }
    public void detach(Player p){measured.remove(p);}
    private synchronized void sample(){
        long now=System.nanoTime();var out=new TreeMap<String,Object>();
        totals.forEach((host,count)->{long bytes=count.sum();var q=windows.computeIfAbsent(host,h->new ArrayDeque<>());
            q.addLast(new Sample(now,bytes));while(q.size()>2&&q.peekFirst().at()<now-60_000_000_000L)q.removeFirst();
            Sample old=q.peekFirst();long delta=Math.max(0,bytes-old.bytes());double elapsed=Math.max(1,(now-old.at())/1e9);
            out.put(host,Map.of("txBytes",ledger.total(host,bytes),"txWindowBytes",delta,"txBytesPerSecond",delta/elapsed,"windowSeconds",Math.min(60,elapsed)));
        });snapshot=Collections.unmodifiableMap(out);
    }
    public Map<String,Object> state(){return snapshot;}
    public int measured(){return measured.size();}
    @Override public void close(){
        closed=true;sampler.shutdown();
        try{if(!sampler.awaitTermination(5,TimeUnit.SECONDS))sampler.shutdownNow();}
        catch(InterruptedException e){Thread.currentThread().interrupt();sampler.shutdownNow();}
        try{persist();log.info("Cumulative route traffic saved");}
        catch(java.io.IOException e){log.error("Final traffic checkpoint failed",e);}
    }
}
