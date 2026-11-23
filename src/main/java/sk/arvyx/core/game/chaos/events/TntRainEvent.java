package sk.arvyx.core.game.chaos.events;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import sk.arvyx.core.arena.Arena;
import sk.arvyx.core.game.chaos.ChaosEvent;
import sk.arvyx.core.game.chaos.ChaosEventContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Chaos udalosť: Dážď TNT (TNT_RAIN).
 */
public final class TntRainEvent implements ChaosEvent {

    public static final String ID = "TNT_RAIN";

    private final Random random = new Random();

    private String name = "Dážď TNT";
    private String description = "Z oblohy nad arénou padajú zapálené TNT bloky.";
    private int durationSeconds = 12;
    private int cooldownSeconds = 40;
    private boolean enabled = true;
    private int tntPerWave = 3;
    private double spawnHeight = 14.0;
    private double spreadRadius = 16.0;
    private int spawnIntervalSeconds = 1;

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void setName(String name) {
        if (name != null && !name.isBlank()) {
            this.name = name;
        }
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public void setDescription(String description) {
        if (description != null && !description.isBlank()) {
            this.description = description;
        }
    }

    @Override
    public int getDurationSeconds() {
        return durationSeconds;
    }

    @Override
    public void setDurationSeconds(int durationSeconds) {
        this.durationSeconds = Math.max(1, durationSeconds);
    }

    @Override
    public int getCooldownSeconds() {
        return cooldownSeconds;
    }

    @Override
    public void setCooldownSeconds(int cooldownSeconds) {
        this.cooldownSeconds = Math.max(0, cooldownSeconds);
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getTntPerWave() {
        return tntPerWave;
    }

    public void setTntPerWave(int tntPerWave) {
        this.tntPerWave = Math.clamp(tntPerWave, 1, 12);
    }

    public double getSpawnHeight() {
        return spawnHeight;
    }

    public void setSpawnHeight(double spawnHeight) {
        this.spawnHeight = Math.clamp(spawnHeight, 4.0, 40.0);
    }

    public double getSpreadRadius() {
        return spreadRadius;
    }

    public void setSpreadRadius(double spreadRadius) {
        this.spreadRadius = Math.clamp(spreadRadius, 2.0, 50.0);
    }

    public int getSpawnIntervalSeconds() {
        return spawnIntervalSeconds;
    }

    public void setSpawnIntervalTicks(int ticks) {
        this.spawnIntervalSeconds = Math.max(1, Math.round(ticks / 20.0f));
    }

    @Override
    public void onActivate(Arena arena, ChaosEventContext context) {
        spawnWave(arena, context);
    }

    @Override
    public void onTick(Arena arena, ChaosEventContext context, int remainingSeconds) {
        if (remainingSeconds % spawnIntervalSeconds == 0) {
            spawnWave(arena, context);
        }
    }

    private void spawnWave(Arena arena, ChaosEventContext context) {
        if (!context.isServerRuntimeAvailable()) {
            return;
        }
        World world = Bukkit.getWorld(arena.getWorldName());
        if (world == null) {
            return;
        }
        List<Location> anchors = new ArrayList<>();
        for (UUID uuid : arena.getAlivePlayers()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline() && player.getWorld().equals(world)) {
                anchors.add(player.getLocation());
            }
        }
        if (anchors.isEmpty()) {
            anchors.add(arena.getCenterLocation().toBukkitLocation(world));
        }

        for (int i = 0; i < tntPerWave; i++) {
            Location anchor = anchors.get(random.nextInt(anchors.size()));
            double offsetX = (random.nextDouble() * 2.0 - 1.0) * spreadRadius;
            double offsetZ = (random.nextDouble() * 2.0 - 1.0) * spreadRadius;
            Location spawnLoc = anchor.clone().add(offsetX, spawnHeight, offsetZ);
            world.spawn(spawnLoc, TNTPrimed.class, tnt -> tnt.setFuseTicks(55));
        }
    }

    @Override
    public void onDeactivate(Arena arena, ChaosEventContext context) {
        if (!context.isServerRuntimeAvailable()) {
            return;
        }
        World world = Bukkit.getWorld(arena.getWorldName());
        if (world == null) {
            return;
        }
        for (TNTPrimed tnt : world.getEntitiesByClass(TNTPrimed.class)) {
            tnt.remove();
        }
    }
}
