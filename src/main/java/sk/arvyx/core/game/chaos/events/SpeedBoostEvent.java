package sk.arvyx.core.game.chaos.events;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import sk.arvyx.core.arena.Arena;
import sk.arvyx.core.game.chaos.ChaosEvent;
import sk.arvyx.core.game.chaos.ChaosEventContext;

import java.util.UUID;

/**
 * Chaos udalosť: Hyper rýchlosť (SPEED_BOOST).
 */
public final class SpeedBoostEvent implements ChaosEvent {

    public static final String ID = "SPEED_BOOST";

    private String name = "Hyper rýchlosť";
    private String description = "Hráči získajú výrazné zrýchlenie pohybu.";
    private int durationSeconds = 15;
    private int cooldownSeconds = 25;
    private boolean enabled = true;
    private int amplifier = 2;

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

    public int getAmplifier() {
        return amplifier;
    }

    public void setAmplifier(int amplifier) {
        this.amplifier = Math.clamp(amplifier, 0, 5);
    }

    @Override
    public void onActivate(Arena arena, ChaosEventContext context) {
        if (!context.isServerRuntimeAvailable()) {
            return;
        }
        PotionEffect effect = new PotionEffect(PotionEffectType.SPEED, durationSeconds * 20, amplifier, true, true, true);
        for (UUID uuid : arena.getAlivePlayers()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                player.addPotionEffect(effect);
            }
        }
    }

    @Override
    public void onDeactivate(Arena arena, ChaosEventContext context) {
        if (!context.isServerRuntimeAvailable()) {
            return;
        }
        for (UUID uuid : arena.getPlayers()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                player.removePotionEffect(PotionEffectType.SPEED);
            }
        }
    }
}
