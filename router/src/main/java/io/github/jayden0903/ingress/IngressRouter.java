package io.github.jayden0903.ingress;

import com.google.gson.*;
import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.*;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.File;
import java.nio.file.Files;
import org.bukkit.NamespacedKey;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * Offline-mode entry server: before the player enters any world it picks the least-used route,
 * stores a one-use signed entry ticket as a cookie and transfers the client to that route's hostname.
 */
public final class IngressRouter extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private record Settings(URI api, long interval, long timeout, long stale, List<RouteBalancer.Target> targets, int statusPort, int publicMaximum,
                            String versionName, int protocolMin, int protocolMax) {}
    /** Cookie key; must match the proxy plugin ({@code ingress:entry}). */
    private static final NamespacedKey COOKIE=new NamespacedKey("ingress","entry");
    private final RouteBalancer balancer=new RouteBalancer();
    private final Object lifecycle=new Object();
    private volatile Settings settings;
    private volatile boolean closed;
    private ScheduledExecutorService poller;
    private ScheduledFuture<?> pollTask;
    private HttpClient http;
    private long lastWarning;
    private EntryTicket tickets;
    private ScheduledExecutorService statusWorker;
    private ScheduledFuture<?> statusTask;
    private volatile BackendStatus backendStatus;

    @Override public void onEnable() {
        saveDefaultConfig();
        http=HttpClient.newBuilder().connectTimeout(Duration.ofMillis(750)).build();
        poller=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"IngressRouter-API");t.setDaemon(true);return t;});
        statusWorker=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"IngressRouter-Status");t.setDaemon(true);return t;});
        try { tickets=new EntryTicket(Files.readAllBytes(getDataFolder().toPath().resolve(getConfig().getString("ticket-key-file","entry-ticket.key")))); apply(readSettings()); }
        catch(Exception e){getLogger().severe("Invalid router config or ticket key: "+e.getMessage());getServer().getPluginManager().disablePlugin(this);return;}
        getServer().getPluginManager().registerEvents(this,this);
        Objects.requireNonNull(getCommand("router")).setExecutor(this);
        getCommand("router").setTabCompleter(this);
        getLogger().info("IngressRouter enabled: transfer before world entry, async route polling");
    }
    private Settings readSettings() {
        YamlConfiguration c=new YamlConfiguration();
        try {c.load(new File(getDataFolder(),"config.yml"));}catch(Exception e){throw new IllegalArgumentException("Cannot read config",e);}
        String host=c.getString("proxy-api.host","127.0.0.1");
        if(!host.equals("127.0.0.1"))throw new IllegalArgumentException("proxy-api.host must be 127.0.0.1");
        int port=c.getInt("proxy-api.port",8765);
        long interval=c.getLong("proxy-api.poll-interval-ms",500),timeout=c.getLong("pending-timeout-ms",10000),stale=c.getLong("proxy-api.stale-after-ms",5000);
        if(port<1||port>65535||interval<100||interval>60000)throw new IllegalArgumentException("Invalid API port/poll interval");
        List<RouteBalancer.Target> routes=new ArrayList<>();
        for(Map<?,?> m:c.getMapList("targets")) {
            Object hp=m.get("host"),pp=m.get("port"),enabled=m.get("enabled");
            if(!(hp instanceof String)||!(pp instanceof Number)||!(enabled instanceof Boolean))throw new IllegalArgumentException("Each target requires host, port, enabled");
            routes.add(new RouteBalancer.Target((String)hp,((Number)pp).intValue(),(Boolean)enabled));
        }
        // Validate the entire configuration before replacing live state.
        new RouteBalancer().configure(routes,timeout,stale);
        int statusPort=c.getInt("server-list.backend-port",25566);
        if(statusPort<1||statusPort>65535)throw new IllegalArgumentException("Invalid server-list.backend-port");
        int maximum=c.getInt("server-list.max-players",100);
        if(maximum<1||maximum>10000)throw new IllegalArgumentException("Invalid server-list.max-players");
        String versionName=c.getString("server-list.version-name","");
        int protocolMin=c.getInt("server-list.protocol-min",0),protocolMax=c.getInt("server-list.protocol-max",0);
        if(versionName!=null&&!versionName.isBlank()&&(protocolMin<0||protocolMax<protocolMin))throw new IllegalArgumentException("Invalid server-list protocol range");
        return new Settings(URI.create("http://127.0.0.1:"+port+"/state"),interval,timeout,stale,List.copyOf(routes),statusPort,maximum,
            versionName==null||versionName.isBlank()?null:versionName,protocolMin,protocolMax);
    }
    private void apply(Settings next) {
        synchronized(lifecycle) {
            balancer.configure(next.targets(),next.timeout(),next.stale());settings=next;
            if(pollTask!=null)pollTask.cancel(false);
            pollTask=poller.scheduleWithFixedDelay(()->poll(next),0,next.interval(),TimeUnit.MILLISECONDS);
            if(statusTask!=null)statusTask.cancel(false);
            statusTask=statusWorker.scheduleWithFixedDelay(()->{
                try{BackendStatus status=BackendStatus.read(next.statusPort(),org.bukkit.Bukkit.getUnsafe().getProtocolVersion());synchronized(lifecycle){if(!closed&&settings==next)backendStatus=status;}}
                catch(Exception unavailable){/* Keep the last MOTD; stale counts are cleared by ping(). */}
            },0,2000,TimeUnit.MILLISECONDS);
        }
    }
    private void poll(Settings forSettings) {
        if(closed||settings!=forSettings)return;
        balancer.expire(System.nanoTime());
        try {
            HttpRequest req=HttpRequest.newBuilder(forSettings.api()).timeout(Duration.ofMillis(1000)).GET().build();
            HttpResponse<String> response=http.send(req,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200||response.body().length()>262144)throw new IllegalStateException("Invalid HTTP response "+response.statusCode());
            JsonObject root=JsonParser.parseString(response.body()).getAsJsonObject();
            Map<String,Integer> counts=new HashMap<>();
            root.getAsJsonObject("counts").entrySet().forEach(e->counts.put(e.getKey(),e.getValue().getAsBigDecimal().intValueExact()));
            Map<UUID,RouteBalancer.Arrival> online=new HashMap<>();
            root.getAsJsonObject("online").entrySet().forEach(e->{JsonObject v=e.getValue().getAsJsonObject();online.put(UUID.fromString(e.getKey()),new RouteBalancer.Arrival(v.get("host").getAsString(),v.get("session").getAsString()));});
            synchronized(lifecycle){if(!closed&&settings==forSettings){
                Map<String,Long> bytes=new HashMap<>(),recent=new HashMap<>();Map<String,Double> rates=new HashMap<>();
                root.getAsJsonObject("traffic").entrySet().forEach(e->{var v=e.getValue().getAsJsonObject();bytes.put(e.getKey(),v.get("txBytes").getAsBigDecimal().longValueExact());recent.put(e.getKey(),v.get("txWindowBytes").getAsLong());rates.put(e.getKey(),v.get("txBytesPerSecond").getAsDouble());});
                balancer.traffic(bytes,recent,rates);balancer.accept(counts,online,System.nanoTime());writeStatus();
            }}
        }catch(InterruptedException e){Thread.currentThread().interrupt();}
        catch(Exception e){long now=System.nanoTime();if(now-lastWarning>30_000_000_000L){lastWarning=now;getLogger().warning("Proxy route API unavailable; last-good then round-robin fallback: "+e.getClass().getSimpleName());}}
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void ping(PaperServerListPingEvent event) {
        BackendStatus status=backendStatus;
        Settings current=settings;
        if(current!=null&&current.versionName()!=null){
            // Advertise the client's own protocol inside the supported range (e.g. behind ViaVersion), else the native one.
            int nativeProtocol=org.bukkit.Bukkit.getUnsafe().getProtocolVersion();
            int requested=event.getClient().getProtocolVersion();
            event.setProtocolVersion(requested>=current.protocolMin()&&requested<=current.protocolMax()?requested:nativeProtocol);
            event.setVersion(current.versionName());
        }
        event.getListedPlayers().clear();event.setHidePlayers(false);
        event.setMaxPlayers(current==null?100:current.publicMaximum());
        if(status==null){event.setNumPlayers(0);return;}
        event.motd(status.motd());
        event.setNumPlayers(System.nanoTime()-status.sampledAt()<=10_000_000_000L?status.online():0);
        event.setServerIcon(status.icon());
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void configure(AsyncPlayerConnectionConfigureEvent event) {
        var connection=event.getConnection();if(closed||!connection.isConnected())return;
        UUID id=UUID.randomUUID();
        String username=connection.getProfile().getName();
        if(username==null){connection.disconnect(Component.text("인증 정보를 확인할 수 없습니다."));return;}
        var reservation=balancer.reserve(id,System.nanoTime());
        try {
            connection.storeCookie(COOKIE,tickets.issue(id,username,reservation.target().host(),System.currentTimeMillis()));
            connection.transfer(reservation.target().host(),reservation.target().port());
            getLogger().info("ROUTE "+id+" -> "+reservation.target().host()+":"+reservation.target().port());
        }catch(RuntimeException e){balancer.release(reservation);connection.disconnect(Component.text("서버 연결에 실패했습니다. 다시 접속해 주세요."));getLogger().log(java.util.logging.Level.WARNING,"Transfer failed",e);}
    }
    private void writeStatus() {
        try { var status=balancer.status(System.nanoTime(),settings.interval());
            var rows=status.rows().stream().map(r->Map.of("host",r.target().host(),"enabled",r.target().enabled(),"online",r.online(),"pending",r.pending(),"txBytes",r.txBytes(),"txWindowBytes",r.txWindowBytes(),"txBytesPerSecond",r.txBytesPerSecond())).toList();
            Files.writeString(getDataFolder().toPath().resolve("status.json"),new Gson().toJson(Map.of("mode",status.mode(),"ageMillis",status.ageMillis(),"updatedAt",System.currentTimeMillis(),"strategy","least cumulative outbound bytes + 256KiB per pending reservation","routes",rows)));
        }catch(Exception ignored){}
    }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        if(!(sender instanceof ConsoleCommandSender)||!sender.hasPermission("ingressrouter.admin")){sender.sendMessage(Component.text("권한이 없습니다.",TextColor.color(0xFF7777)));return true;}
        if(args.length==1&&args[0].equalsIgnoreCase("reload")){
            try{apply(readSettings());sender.sendMessage(Component.text("Router │ 설정을 다시 불러왔습니다.",TextColor.color(0xFFC99E)));}
            catch(RuntimeException e){sender.sendMessage(Component.text("설정을 적용하지 못했습니다: "+e.getMessage(),TextColor.color(0xFF7777)));}return true;
        }
        if(args.length>1||(args.length==1&&!args[0].equalsIgnoreCase("status"))){sender.sendMessage("/router status | /router reload");return true;}
        var status=balancer.status(System.nanoTime(),settings.interval());
        sender.sendMessage(Component.text("Router │ 누적 송신량 기준 · "+status.mode()+" · API "+(status.ageMillis()<0?"미수신":status.ageMillis()+"ms 전"),TextColor.color(0xFFC99E)));
        for(var row:status.rows())sender.sendMessage(Component.text(String.format(Locale.ROOT,"  %s:%d  실제 %d · pending %d · %s · 누적 %.2f MiB · %.1f KiB/s",row.target().host(),row.target().port(),row.online(),row.pending(),row.target().enabled()?"활성":"비활성",row.txBytes()/1048576.0,row.txBytesPerSecond()/1024),TextColor.color(row.target().enabled()?0xFFF3DF:0xA88770)));
        return true;
    }
    @Override public List<String> onTabComplete(CommandSender s,Command c,String l,String[] a){return s instanceof ConsoleCommandSender&&s.hasPermission("ingressrouter.admin")&&a.length==1?List.of("status","reload").stream().filter(x->x.startsWith(a[0].toLowerCase(Locale.ROOT))).toList():List.of();}
    @Override public void onDisable(){synchronized(lifecycle){closed=true;if(pollTask!=null)pollTask.cancel(true);if(poller!=null)poller.shutdownNow();if(statusTask!=null)statusTask.cancel(true);if(statusWorker!=null)statusWorker.shutdownNow();if(http!=null)http.shutdownNow();}}
}
