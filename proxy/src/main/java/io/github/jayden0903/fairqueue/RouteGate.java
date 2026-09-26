package io.github.jayden0903.fairqueue;

import com.google.gson.Gson;
import com.velocitypowered.api.event.*;
import com.velocitypowered.api.event.connection.*;
import com.velocitypowered.api.event.player.CookieReceiveEvent;
import com.velocitypowered.api.network.HandshakeIntent;
import com.velocitypowered.api.proxy.*;
import com.sun.net.httpserver.*;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import io.github.jayden0903.ingress.EntryTicket;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Validates entry tickets after online authentication, before any backend connection, and serves the
 * loopback route API the router polls.
 */
public final class RouteGate implements AutoCloseable {
    /** Cookie key; must match the router ({@code ingress:entry}). */
    static final Key COOKIE=Key.key("ingress:entry");
    static final String DEFAULT_ROUTES="s1.example.com,s2.example.com,s3.example.com,s4.example.com,s5.example.com,s6.example.com,s7.example.com,s8.example.com,s9.example.com,s10.example.com";
    private final Component denial;
    private final EntryTicket tickets;
    private final long startedAt=System.currentTimeMillis();
    private final ConcurrentHashMap<Player,CompletableFuture<byte[]>> requests=new ConcurrentHashMap<>();
    private final Map<UUID,Long> consumed=new HashMap<>();
    private final Map<Player,EntryTicket.Ticket> verified=new HashMap<>();
    private final Map<Player,EntryTicket.Ticket> online=new HashMap<>();
    private final List<String> known;
    private final HttpServer api;
    private final RouteTraffic traffic;
    private final ThreadPoolExecutor workers;
    private volatile boolean closed;

    public RouteGate(Properties c,Path data,org.slf4j.Logger log,String serverBrand) throws Exception {
        denial=Component.text(c.getProperty("entry-host","entry.example.com").trim()+" 주소로 접속해 주세요.");
        tickets=new EntryTicket(Files.readAllBytes(data.resolve(c.getProperty("ticket-key-file","entry-ticket.key").trim())));
        known=Arrays.stream(c.getProperty("route-hosts",DEFAULT_ROUTES).split(",")).map(String::trim).filter(s->!s.isEmpty()).map(s->s.toLowerCase(Locale.ROOT)).toList();
        traffic=new RouteTraffic(known,data.resolve(c.getProperty("traffic-file","traffic-totals.properties").trim()),log,serverBrand);
        int port=Integer.parseInt(c.getProperty("route-api-port","8765").trim());
        api=HttpServer.create(new InetSocketAddress("127.0.0.1",port),16);
        workers=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(32),r->{Thread t=new Thread(r,"FairQueue-RouteAPI");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
        api.setExecutor(workers);api.createContext("/",this::respond);api.start();
    }
    @Subscribe(order=PostOrder.FIRST) public void prelogin(PreLoginEvent e) {
        if(closed||e.getConnection().getHandshakeIntent()!=HandshakeIntent.TRANSFER)
            e.setResult(PreLoginEvent.PreLoginComponentResult.denied(denial));
    }
    @Subscribe(order=PostOrder.LAST) public EventTask login(LoginEvent e) {
        if(!e.getResult().isAllowed())return null;
        Player p=e.getPlayer();
        if(closed||p.getHandshakeIntent()!=HandshakeIntent.TRANSFER){e.setResult(ResultedEvent.ComponentResult.denied(denial));return null;}
        CompletableFuture<byte[]> f=new CompletableFuture<>();requests.put(p,f);
        CompletableFuture<Void> done=f.orTimeout(4,TimeUnit.SECONDS).handle((bytes,error)->{
            requests.remove(p,f);
            String host=p.getVirtualHost().map(a->a.getHostString().toLowerCase(Locale.ROOT).replaceAll("\\.$","")).orElse("");
            EntryTicket.Ticket t=error==null?tickets.verify(bytes,p.getUsername(),host,System.currentTimeMillis()):null;
            synchronized(this) {
                long now=System.currentTimeMillis();consumed.entrySet().removeIf(x->x.getValue()<=now);
                if(closed||!p.isActive()||t==null||t.issued()<startedAt||consumed.containsKey(t.nonce())||consumed.size()>=10000) {
                    e.setResult(ResultedEvent.ComponentResult.denied(denial));
                }else {consumed.put(t.nonce(),t.expires());verified.put(p,t);}
            }
            return null;
        });
        try{p.requestCookie(COOKIE);}catch(RuntimeException failure){f.completeExceptionally(failure);}
        return EventTask.resumeWhenComplete(done);
    }
    @Subscribe public void cookie(CookieReceiveEvent e) {
        if(!e.getOriginalKey().equals(COOKIE))return;
        e.setResult(CookieReceiveEvent.ForwardResult.handled());
        CompletableFuture<byte[]> f=requests.get(e.getPlayer());
        if(f!=null)f.complete(e.getOriginalData());
    }
    @Subscribe public synchronized void joined(PostLoginEvent e) {
        EntryTicket.Ticket t=verified.remove(e.getPlayer());
        if(t==null){e.getPlayer().disconnect(denial);return;}
        online.put(e.getPlayer(),t);
        traffic.attach(e.getPlayer(),t.host());
    }
    @Subscribe public void disconnected(DisconnectEvent e) {
        CompletableFuture<byte[]> f=requests.remove(e.getPlayer());if(f!=null)f.completeExceptionally(new IllegalStateException("Disconnected"));
        synchronized(this){verified.remove(e.getPlayer());online.remove(e.getPlayer());traffic.detach(e.getPlayer());}
    }
    private synchronized Map<String,Object> state() {
        Map<String,Integer> counts=new TreeMap<>();known.forEach(h->counts.put(h,0));
        Map<String,Object> arrivals=new TreeMap<>();
        online.forEach((p,t)->{
            counts.merge(t.host(),1,Integer::sum);
            arrivals.put(t.nonce().toString(),Map.of("host",t.host(),"session",t.nonce().toString()));
        });
        return Map.of("counts",counts,"online",arrivals,"traffic",traffic.state(),"measuredConnections",traffic.measured(),"sampledAt",System.currentTimeMillis(),"measurement","encoded downstream bytes; excludes TCP headers and retransmissions");
    }
    private void respond(HttpExchange x) throws java.io.IOException {
        try {
            String path=x.getRequestURI().getPath();int code=200;Object body;
            if(!x.getRequestMethod().equals("GET")){code=405;body=Map.of("error","Method not allowed");}
            else if(path.equals("/health"))body=Map.of("status","ok");
            else if(path.equals("/state"))body=state();
            else if(path.equals("/")||path.equals("/counts"))body=state().get("counts");
            else{code=404;body=Map.of("error","Not found");}
            byte[] raw=new Gson().toJson(body).getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");x.getResponseHeaders().set("Cache-Control","no-store");
            x.sendResponseHeaders(code,raw.length);x.getResponseBody().write(raw);
        }finally{x.close();}
    }
    public void close(){closed=true;traffic.close();api.stop(0);workers.shutdownNow();requests.values().forEach(f->f.completeExceptionally(new IllegalStateException("Shutdown")));requests.clear();}
}
