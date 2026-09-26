package io.github.jayden0903.fairqueue;

import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Called under the admission lock, so an old waiting frame cannot follow admission cleanup. */
final class QueuePresentation {
    private static final TextColor PEACH=TextColor.color(0xFFC99E), CREAM=TextColor.color(0xFFF3DF),
        MUTED=TextColor.color(0xA6B3BE), MINT=TextColor.color(0xB8DDA8), LILAC=TextColor.color(0xC5B5EF);
    record Frame(Component title,Component subtitle,Component action,Component bar,float occupancy) {}
    private static final class View { BossBar bar; Component title,subtitle; long shown; }
    private final Map<UUID,View> views=new HashMap<>();
    /** Chat hint colours: label peach, text cream, the optional how-to suffix a warm muted tone. */
    private static final TextColor HINT_MUTED=TextColor.color(0xC8B49A);
    static final long HINT_INTERVAL_MILLIS=3*60_000L;
    /** Last priority-hint time per player (monotonic ms); removed only on disconnect via {@link #forget}. */
    private final Map<UUID,Long> hints=new HashMap<>();
    /** Short name shown in the boss bar and the hint line (config {@code display-name}). */
    private final String label;
    /** Optional text appended to the hint, e.g. how to get priority (config {@code priority-hint-suffix}). */
    private final String hintSuffix;

    QueuePresentation(String label,String hintSuffix) {
        this.label=label==null||label.isBlank()?"대기열":label;
        this.hintSuffix=hintSuffix==null?"":hintSuffix;
    }
    /**
     * Whether a waiting player should see the priority hint: only normal players, only while the main
     * backend is healthy, and only when a priority player joining now ({@code priorityPosition}) would be
     * at least 3 places ahead of this player's real position.
     */
    static boolean priorityHint(boolean prioritized,boolean healthy,int position,int priorityPosition) {
        return !prioritized&&healthy&&position>0&&priorityPosition>0&&position-priorityPosition>=3;
    }
    /** First time in the queue, or at least 3 minutes since the last hint. */
    static boolean hintDue(Long lastShownMillis,long nowMillis) {
        return lastShownMillis==null||nowMillis-lastShownMillis>=HINT_INTERVAL_MILLIS;
    }
    static Component hintLine(String label,int priorityPosition,String suffix) {
        Component line=Component.text(" "+label+" │ ",PEACH)
            .append(Component.text("우선 입장 대상은 지금 들어와도 "+priorityPosition+"번째예요",CREAM));
        return suffix==null||suffix.isBlank()?line:line.append(Component.text(" · "+suffix,HINT_MUTED));
    }
    /** Sends the one-line hint when eligible and due; called under the admission lock from the 1 s display loop. */
    void hint(Player player,boolean eligible,int priorityPosition,long nowMillis) {
        if(!eligible||!hintDue(hints.get(player.getUniqueId()),nowMillis))return;
        hints.put(player.getUniqueId(),nowMillis);
        player.sendMessage(hintLine(label,priorityPosition,hintSuffix));
    }
    void forget(UUID id) {hints.remove(id);}
    Frame frame(int position,int online,int capacity,int waiting,boolean healthy,boolean prioritized,Long etaSeconds) {
        return frame(label,position,online,capacity,waiting,healthy,prioritized,etaSeconds);
    }
    /** etaSeconds: AdmissionRate estimate for this position, null while the rate is still unknown. */
    static Frame frame(String label,int position,int online,int capacity,int waiting,boolean healthy,boolean prioritized,Long etaSeconds) {
        Component title=position==0?Component.text("입장 중",MINT).decorate(TextDecoration.BOLD)
            :Component.text("대기 ",CREAM).append(Component.text(position,PEACH).decorate(TextDecoration.BOLD)).append(Component.text("번",CREAM));
        // The estimate is shown only while admissions can actually happen; during an outage the
        // existing "server not ready" line stays alone. It is rounded to whole minutes so the title
        // is re-sent at most when the minute label changes.
        Component subtitle=position==0?Component.text("곧 서버로 이동해요.",CREAM)
            :!healthy?Component.text("서버가 준비되면 입장해요.",CREAM)
            :Component.text(AdmissionRate.label(etaSeconds),etaSeconds==null?MUTED:PEACH)
                .append(Component.text("  ·  ",MUTED)).append(Component.text("차례가 되면 자동으로 입장해요.",CREAM));
        Component action=Component.text(prioritized?"우선 입장":"일반 대기",prioritized?LILAC:MUTED)
            .append(Component.text("  ·  ",MUTED)).append(Component.text("접속 ",MUTED))
            .append(Component.text(online+" / "+capacity,CREAM)).append(Component.text("명",MUTED));
        Component bar=Component.text(label,PEACH).decorate(TextDecoration.BOLD)
            .append(Component.text("  ·  ",MUTED).decoration(TextDecoration.BOLD,false))
            .append(Component.text(healthy?"접속 "+online+" / "+capacity+"명":"서버 연결 대기",CREAM).decoration(TextDecoration.BOLD,false))
            .append(Component.text("  ·  대기 "+waiting+"명",MUTED).decoration(TextDecoration.BOLD,false));
        // This bar represents actual server occupancy, never an invented wait-time estimate.
        return new Frame(title,subtitle,action,bar,Math.max(0f,Math.min(1f,online/(float)Math.max(1,capacity))));
    }
    void show(Player player,Frame frame) {
        View view=views.computeIfAbsent(player.getUniqueId(),id->new View());
        long now=System.nanoTime();
        if(!frame.title.equals(view.title)||!frame.subtitle.equals(view.subtitle)||now-view.shown>Duration.ofSeconds(55).toNanos()){
            player.showTitle(Title.title(frame.title,frame.subtitle,Title.Times.times(
                view.title==null?Duration.ofMillis(250):Duration.ZERO,Duration.ofSeconds(60),Duration.ofMillis(250))));
            view.title=frame.title;view.subtitle=frame.subtitle;view.shown=now;
        }
        if(view.bar==null){
            view.bar=BossBar.bossBar(frame.bar,frame.occupancy,BossBar.Color.YELLOW,BossBar.Overlay.PROGRESS);
            player.showBossBar(view.bar);
        }else{
            if(!view.bar.name().equals(frame.bar))view.bar.name(frame.bar);
            if(view.bar.progress()!=frame.occupancy)view.bar.progress(frame.occupancy);
        }
        player.sendActionBar(frame.action);
    }
    void clear(Player player) {
        View view=views.remove(player.getUniqueId());
        if(view!=null){if(view.bar!=null)player.hideBossBar(view.bar);player.clearTitle();player.sendActionBar(Component.empty());}
    }
}
