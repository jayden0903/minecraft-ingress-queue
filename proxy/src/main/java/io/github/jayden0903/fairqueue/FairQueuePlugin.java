package io.github.jayden0903.fairqueue;

import com.google.inject.Inject;
import com.google.gson.*;
import com.velocitypowered.api.event.*;
import com.velocitypowered.api.event.connection.*;
import com.velocitypowered.api.event.player.*;
import com.velocitypowered.api.event.proxy.*;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.*;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerPing;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.slf4j.Logger;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Strict-capacity admission for one main backend, with a void waiting lobby, a 3:1 priority lane,
 * a measured-throughput ETA and one-use entry-ticket verification ({@link RouteGate}).
 */
public final class FairQueuePlugin {
    private static final TextColor GOLD=TextColor.color(0xE8A044), CREAM=TextColor.color(0xFFF3DF);
    private static final String DEFAULT_MOTD="A Minecraft Server";
    private final ProxyServer proxy;
    private final Logger log;
    private final Path data;
    private final AtomicBoolean pinging=new AtomicBoolean();
    private final AtomicBoolean draining=new AtomicBoolean(), drainScheduled=new AtomicBoolean();
    private volatile boolean ready, healthy;
    /** Closed until the first successful read of the access files (deny by default). */
    private volatile Access access=new Access(true,Set.of(),Set.of(),Set.of());
    private volatile Component motd=Component.text(DEFAULT_MOTD,GOLD);
    private AdmissionQueue queue;
    private final PrioritySnapshot priorityMembers=new PrioritySnapshot();
    private QueuePresentation presentation;
    private final AdmissionRate admissionRate=new AdmissionRate();
    private volatile Set<UUID> administrators=Set.of();
    private final ConcurrentHashMap<UUID,Player> presence=new ConcurrentHashMap<>();
    private RouteGate gate;
    private RegisteredServer main,lobby;
    private Path accessRoot, priorityFile, statusFile;
    private String configuredMotd, pingVersionName;
    private int pingProtocolMin, pingProtocolMax;
    private int capacity;
    private record Access(boolean whitelist,Set<UUID> allowed,Set<UUID> banned,Set<String> bannedIps) {}

    @Inject public FairQueuePlugin(ProxyServer proxy,Logger log,@DataDirectory Path data) { this.proxy=proxy;this.log=log;this.data=data; }
    @Subscribe public void initialize(ProxyInitializeEvent event) {
        try {
            Files.createDirectories(data);
            Path configFile=data.resolve("config.properties");
            if(!Files.exists(configFile))try(InputStream defaults=FairQueuePlugin.class.getResourceAsStream("/config.properties")){
                Files.copy(Objects.requireNonNull(defaults,"bundled config.properties missing"),configFile);
            }
            Properties config=properties(configFile);
            capacity=Integer.parseInt(config.getProperty("capacity","100").trim());
            queue=new AdmissionQueue(capacity);
            String mainName=config.getProperty("main-server","survival").trim(),lobbyName=config.getProperty("lobby-server","queue").trim();
            main=proxy.getServer(mainName).orElseThrow(()->new IllegalStateException("Velocity server '"+mainName+"' (main-server) is not defined"));
            lobby=proxy.getServer(lobbyName).orElseThrow(()->new IllegalStateException("Velocity server '"+lobbyName+"' (lobby-server) is not defined"));
            presentation=new QueuePresentation(config.getProperty("display-name","대기열").trim(),config.getProperty("priority-hint-suffix","").trim());
            configuredMotd=config.getProperty("motd",DEFAULT_MOTD);
            motd=LegacyComponentSerializer.legacySection().deserialize(configuredMotd);
            pingVersionName=blankToNull(config.getProperty("ping-version-name"));
            pingProtocolMin=Integer.parseInt(config.getProperty("ping-protocol-min","0").trim());
            pingProtocolMax=Integer.parseInt(config.getProperty("ping-protocol-max","0").trim());
            String root=blankToNull(config.getProperty("access-root"));
            accessRoot=root==null?null:data.resolve(root);
            priorityFile=data.resolve(config.getProperty("priority-file","priority.json").trim());
            statusFile=data.resolve(config.getProperty("status-file","status.json").trim());
            if(accessRoot!=null)refreshAccess();
            else access=new Access(false,Set.of(),Set.of(),Set.of());
            try{refreshPriority();}catch(Exception e){log.warn("Priority snapshot unavailable; standard queue remains available");}
            gate=new RouteGate(config,data,log,blankToNull(config.getProperty("server-brand")));
            proxy.getEventManager().register(this,gate);
            ready=true;
            proxy.getScheduler().buildTask(this,()->safe(this::tick)).repeat(Duration.ofMillis(50)).schedule();
            proxy.getScheduler().buildTask(this,()->safe(this::display)).repeat(Duration.ofSeconds(1)).schedule();
            proxy.getScheduler().buildTask(this,()->safe(this::health)).repeat(Duration.ofSeconds(2)).schedule();
            if(accessRoot!=null)proxy.getScheduler().buildTask(this,()->safe(this::refreshAccess)).repeat(Duration.ofSeconds(2)).schedule();
            proxy.getScheduler().buildTask(this,()->safe(this::refreshPriority)).repeat(Duration.ofSeconds(2)).schedule();
            log.info("Fair-priority queue ready: {} capacity {}, access policy {}",mainName,capacity,accessRoot==null?"disabled":"enabled");
        } catch(Exception e) { log.error("Queue initialization failed; new logins remain closed",e); }
    }
    @Subscribe public void shutdown(ProxyShutdownEvent e){ready=false;if(gate!=null)gate.close();}
    private interface Task {void run() throws Exception;}
    private void safe(Task task) {try{task.run();}catch(Exception e){log.error("Queue task failed",e);}}
    private static String blankToNull(String value){return value==null||value.isBlank()?null:value.trim();}
    private static Properties properties(Path file) throws IOException {
        Properties p=new Properties();try(Reader r=Files.newBufferedReader(file)){p.load(r);}return p;
    }
    private static JsonArray array(Path file) throws IOException {try(Reader r=Files.newBufferedReader(file)){return JsonParser.parseReader(r).getAsJsonArray();}}
    /** Mirrors the main backend's whitelist, ops, bans and MOTD so a denied player never takes a queue slot. */
    private void refreshAccess() throws IOException {
        Properties p=properties(accessRoot.resolve("server.properties"));
        Set<UUID> allowed=new HashSet<>(),banned=new HashSet<>();Set<String> ips=new HashSet<>();
        for(String file:List.of("ops.json","whitelist.json"))for(JsonElement e:array(accessRoot.resolve(file)))allowed.add(UUID.fromString(e.getAsJsonObject().get("uuid").getAsString()));
        for(JsonElement e:array(accessRoot.resolve("banned-players.json")))if(unexpired(e.getAsJsonObject()))banned.add(UUID.fromString(e.getAsJsonObject().get("uuid").getAsString()));
        for(JsonElement e:array(accessRoot.resolve("banned-ips.json")))if(unexpired(e.getAsJsonObject()))ips.add(e.getAsJsonObject().get("ip").getAsString());
        Set<UUID> ops=new HashSet<>();
        for(JsonElement e:array(accessRoot.resolve("ops.json")))ops.add(UUID.fromString(e.getAsJsonObject().get("uuid").getAsString()));
        administrators=Set.copyOf(ops);
        access=new Access(Boolean.parseBoolean(p.getProperty("white-list","true")),Set.copyOf(allowed),Set.copyOf(banned),Set.copyOf(ips));
        motd=LegacyComponentSerializer.legacySection().deserialize(p.getProperty("motd",configuredMotd));
    }
    private void refreshPriority() throws Exception {
        priorityMembers.read(priorityFile);
        queue.priorities(priorityMembers.priorities());
    }
    private static boolean unexpired(JsonObject e) {
        if(!e.has("expires")||e.get("expires").getAsString().equalsIgnoreCase("forever"))return true;
        try{return ZonedDateTime.parse(e.get("expires").getAsString(),DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z",Locale.ROOT)).toInstant().isAfter(Instant.now());}
        catch(RuntimeException invalid){return true;}
    }
    @Subscribe public void login(LoginEvent e) {
        Access a=access;Player p=e.getPlayer();
        if(!ready)e.setResult(ResultedEvent.ComponentResult.denied(Component.text("서버 준비 중입니다. 잠시 후 다시 접속해 주세요.",CREAM)));
        else if(a.banned.contains(p.getUniqueId())||a.bannedIps.contains(p.getRemoteAddress().getAddress().getHostAddress()))e.setResult(ResultedEvent.ComponentResult.denied(Component.text("서버 접속이 제한된 계정 또는 주소입니다.",CREAM)));
        else if(a.whitelist&&!a.allowed.contains(p.getUniqueId()))e.setResult(ResultedEvent.ComponentResult.denied(Component.text("서버가 현재 화이트리스트로 운영 중입니다.",CREAM)));
    }
    private Set<UUID> actual() {return main.getPlayersConnected().stream().map(Player::getUniqueId).collect(Collectors.toSet());}
    @Subscribe public void initial(PlayerChooseInitialServerEvent e) {
        if(!ready)return;
        boolean admit=queue.initial(e.getPlayer().getUniqueId(),actual(),healthy);
        e.setInitialServer(admit?main:lobby);
    }
    @Subscribe(order=PostOrder.LAST) public void preconnect(ServerPreConnectEvent e) {
        if(!ready){e.setResult(ServerPreConnectEvent.ServerResult.denied());return;}
        // Respect cancellation by other access-control plugins.
        if(e.getResult().getServer().isEmpty())return;
        RegisteredServer target=e.getResult().getServer().get();UUID id=e.getPlayer().getUniqueId();
        if(target.equals(main)&&!queue.reserved(id)) {
            if(e.getPlayer().getCurrentServer().map(s->s.getServer().equals(main)).orElse(false)) {e.setResult(ServerPreConnectEvent.ServerResult.denied());return;}
            queue.enqueue(id);
            if(e.getPlayer().getCurrentServer().map(s->s.getServer().equals(lobby)).orElse(false))e.setResult(ServerPreConnectEvent.ServerResult.denied());
            else e.setResult(ServerPreConnectEvent.ServerResult.allowed(lobby));
        } else if(target.equals(lobby)){queue.failed(id);queue.enqueue(id);}
        else if(!target.equals(main))e.setResult(ServerPreConnectEvent.ServerResult.denied());
    }
    @Subscribe public void connected(ServerConnectedEvent e) {
        if(!ready)return;Player p=e.getPlayer();UUID id=p.getUniqueId();
        if(e.getServer().equals(main)) {
            // Only lobby-to-main moves are queue throughput; AdmissionRate further ignores them
            // unless the queue was seat-limited (instant admissions into free seats never count).
            if(e.getPreviousServer().map(lobby::equals).orElse(false))admissionRate.admitted(monotonicMillis());
            queue.admitted(id);clear(p);
            log.info("ADMITTED {} main={}/{}",p.getUsername(),queue.occupied(actual()),capacity);
        } else if(e.getServer().equals(lobby)) {queue.queued(id);log.info("QUEUED {} position={}",p.getUsername(),queue.position(id));}
        requestDrain();
    }
    @Subscribe public void presenceConnected(ServerPostConnectEvent e) {
        Player p=e.getPlayer();
        if(ready&&p.isActive()&&presence.put(p.getUniqueId(),p)!=p)broadcastPresence(p,true);
    }
    private void broadcastPresence(Player player,boolean joining) {
        var member=priorityMembers.active(player.getUniqueId());
        if(joining&&member!=null&&member.joining().equals("끄기"))return;
        Component message=PresenceMessage.create(player.getUsername(),administrators.contains(player.getUniqueId()),joining);
        if(joining&&member!=null&&!member.joining().equals("기본")){
            String suffix=switch(member.joining()){case "인사"->"님이 접속했어요.";case "반가워요"->"님, 반가워요!";case "함께해요"->"님이 함께합니다.";default->"";};
            message=Component.text("[",TextColor.color(0x9AA3AD)).append(Component.text("+",TextColor.color(0x85D6A0))).append(Component.text("] ",TextColor.color(0x9AA3AD)))
                .append(PresenceMessage.name(player.getUsername(),TextColor.fromHexString("#"+member.color()),member.gradient())).append(Component.text(suffix,CREAM));
        }
        Component line=message;proxy.getAllPlayers().forEach(target->target.sendMessage(line));
    }
    @Subscribe public void disconnected(DisconnectEvent e) {
        if(presence.remove(e.getPlayer().getUniqueId(),e.getPlayer()))broadcastPresence(e.getPlayer(),false);
        if(queue!=null&&presentation!=null){synchronized(queue){queue.departed(e.getPlayer().getUniqueId());presentation.clear(e.getPlayer());presentation.forget(e.getPlayer().getUniqueId());}}requestDrain();
    }
    @Subscribe public void kicked(KickedFromServerEvent e) {
        if(!ready)return;
        // A deliberate backend kick, ban or whitelist denial must not create an endless retry loop.
        e.setResult(KickedFromServerEvent.DisconnectPlayer.create(e.getServerKickReason().orElse(Component.text("서버 연결이 종료되었습니다.",CREAM))));
    }
    private void health() {
        if(!pinging.compareAndSet(false,true))return;
        main.ping().orTimeout(3,TimeUnit.SECONDS).whenComplete((pong,error)->{healthy=error==null;pinging.set(false);requestDrain();});
    }
    private void requestDrain() {
        if(!ready||!drainScheduled.compareAndSet(false,true))return;
        proxy.getScheduler().buildTask(this,()->{drainScheduled.set(false);safe(this::tick);}).schedule();
    }
    private void tick() {
        if(!ready||!draining.compareAndSet(false,true))return;
        try {
            for(UUID id:queue.expired(TimeUnit.SECONDS.toNanos(30)))proxy.getPlayer(id).ifPresent(p->{
                if(queue.reserved(id))p.disconnect(Component.text("입장 시간이 초과됐습니다. 다시 접속해 주세요.",CREAM));
            });
            if(queue.size()==0)return;
            Set<UUID> eligible=lobby.getPlayersConnected().stream().map(Player::getUniqueId).collect(Collectors.toSet());
            Set<UUID> actual=actual();
            // Fill all free seats in this pass without intentional per-player pacing.
            // The finite seat count bounds the loop; no zero-delay timer or busy waiting is used.
            for(int i=0;i<capacity;i++) {
                Optional<UUID> next=queue.next(eligible,actual,healthy);
                if(next.isEmpty())return;
                UUID id=next.get();Optional<Player> found=proxy.getPlayer(id);
                if(found.isEmpty()){queue.departed(id);continue;}
                Player p=found.get();
                try {
                    // Remove queue UI while the client is still in PLAY. During backend
                    // reconfiguration Velocity deliberately drops boss-bar packets.
                    clear(p);
                    p.createConnectionRequest(main).connect().whenComplete((result,error)->{
                        if(error!=null){queue.failed(id);healthy=false;log.info("Backend unavailable; FIFO position retained for {}",p.getUsername());return;}
                        if(result.isSuccessful())return;
                        if(result.getStatus()==ConnectionRequestBuilder.Status.ALREADY_CONNECTED){queue.admitted(id);clear(p);requestDrain();return;}
                        queue.failed(id);
                        if(result.getStatus()==ConnectionRequestBuilder.Status.SERVER_DISCONNECTED)
                            p.disconnect(result.getReasonComponent().orElse(Component.text("서버에 입장할 수 없습니다.",CREAM)));
                        else healthy=false;
                    });
                } catch(RuntimeException error){queue.failed(id);healthy=false;return;}
            }
        } finally {draining.set(false);}
    }
    private void clear(Player p) {synchronized(queue){presentation.clear(p);}}
    private static long monotonicMillis() {return TimeUnit.NANOSECONDS.toMillis(System.nanoTime());}
    private void display() throws IOException {
        if(!ready)return;
        int occupied=actual().size(),total=queue.size(),withReservations=queue.occupied(actual());
        boolean up=healthy;long now=monotonicMillis();
        admissionRate.observe(now,up&&total>0&&withReservations>=capacity);
        Double perMinute=admissionRate.perMinute(now);
        synchronized(queue) {
        int priorityPosition=queue.priorityPosition();
        for(Player p:lobby.getPlayersConnected()) {
            if(!p.isActive())continue;
            int position=queue.position(p.getUniqueId());if(position<=0)continue;
            boolean prioritized=priorityMembers.active(p.getUniqueId())!=null;
            presentation.show(p,presentation.frame(position,occupied,capacity,total,up,prioritized,AdmissionRate.etaSeconds(position,perMinute)));
            presentation.hint(p,QueuePresentation.priorityHint(prioritized,up,position,priorityPosition),priorityPosition,now);
        }
        }
        JsonObject state=new JsonObject();state.addProperty("capacity",capacity);state.addProperty("main",occupied);state.addProperty("occupiedWithReservations",withReservations);state.addProperty("waiting",total);state.addProperty("pending",queue.pending());state.addProperty("healthy",healthy);state.addProperty("updatedAt",System.currentTimeMillis());
        state.addProperty("admissionIntervalMs",0);
        state.addProperty("priorityLane",true);state.addProperty("priorityBurst",3);
        JsonObject eta=new JsonObject();
        eta.addProperty("perMinute",perMinute==null?null:Math.round(perMinute*100)/100d);
        eta.addProperty("newcomerSeconds",AdmissionRate.newcomerSeconds(up,total,withReservations,capacity,queue.newcomerPosition(),perMinute));
        state.add("eta",eta);
        // serializeNulls keeps "perMinute"/"newcomerSeconds" present as JSON null while unknown;
        // every other field is a non-null primitive, so their output is unchanged.
        Path temporary=statusFile.resolveSibling(statusFile.getFileName()+".tmp");Files.writeString(temporary,new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(state));Files.move(temporary,statusFile,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
    }
    @Subscribe public void ping(ProxyPingEvent e) {
        if(!ready)return;
        var builder=e.getPing().asBuilder();
        if(pingVersionName!=null){
            // Advertise the client's own protocol inside the supported range (e.g. behind ViaVersion), else the maximum.
            int requested=e.getConnection().getProtocolVersion().getProtocol();
            int advertised=requested>=pingProtocolMin&&requested<=pingProtocolMax?requested:pingProtocolMax;
            builder.version(new ServerPing.Version(advertised,pingVersionName));
        }
        e.setPing(builder.description(motd).onlinePlayers(actual().size()).maximumPlayers(capacity).clearSamplePlayers().build());
    }
}
