package io.github.jayden0903.fairqueue.lobby;

import java.util.Random;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.event.entity.*;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;
import io.papermc.paper.event.player.AsyncChatEvent;

public final class QueueLobby extends JavaPlugin implements Listener {
    @Override public ChunkGenerator getDefaultWorldGenerator(String worldName, String id) {
        return new ChunkGenerator() {
            @Override public boolean shouldGenerateNoise() { return false; }
            @Override public boolean shouldGenerateSurface() { return false; }
            @Override public boolean shouldGenerateBedrock() { return false; }
            @Override public boolean shouldGenerateCaves() { return false; }
            @Override public boolean shouldGenerateDecorations() { return false; }
            @Override public boolean shouldGenerateMobs() { return false; }
            @Override public boolean shouldGenerateStructures() { return false; }
            @Override public Location getFixedSpawnLocation(World world, Random random) { return new Location(world, 0.5, 128, 0.5); }
        };
    }
    @Override public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        for (World world : getServer().getWorlds()) {
            world.setSpawnLocation(0,128,0);
            world.setTime(18000);
            world.setStorm(false);
            world.setThundering(false);
            world.setDifficulty(Difficulty.PEACEFUL);
        }
    }
    @EventHandler public void join(PlayerJoinEvent event) {
        event.joinMessage(null);
        Player p = event.getPlayer();
        p.setGameMode(GameMode.SPECTATOR);
        p.setInvulnerable(true);
        p.setSilent(true);
        p.setFoodLevel(20);
        p.teleport(new Location(p.getWorld(),0.5,128,0.5,0,0));
        p.setPlayerTime(18000,false);
        p.setPlayerWeather(WeatherType.CLEAR);
    }
    @EventHandler public void quit(PlayerQuitEvent event) { event.quitMessage(null); }
    @EventHandler public void damage(EntityDamageEvent event) { if (event.getEntity() instanceof Player) event.setCancelled(true); }
    @EventHandler public void food(FoodLevelChangeEvent event) { event.setCancelled(true); }
    @EventHandler public void chat(AsyncChatEvent event) { event.setCancelled(true); }
    @EventHandler public void move(PlayerMoveEvent event) {
        if (event instanceof PlayerTeleportEvent || event.getTo() == null) return;
        Location to=event.getTo(), from=event.getFrom();
        if (from.getX()!=to.getX() || from.getY()!=to.getY() || from.getZ()!=to.getZ()) {
            Location fixed=from.clone(); fixed.setYaw(to.getYaw()); fixed.setPitch(to.getPitch()); event.setTo(fixed);
        }
    }
    @EventHandler public void command(PlayerCommandPreprocessEvent event) { event.setCancelled(true); }
}
