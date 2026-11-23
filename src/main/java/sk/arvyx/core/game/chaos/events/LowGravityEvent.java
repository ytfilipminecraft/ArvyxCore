package sk.arvyx.core.game.chaos.events;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlotGroup;
import sk.arvyx.core.arena.Arena;
import sk.arvyx.core.game.chaos.ChaosEvent;
import sk.arvyx.core.game.chaos.ChaosEventContext;

import java.util.UUID;

/**
 * Chaos udalosť: Nízka gravitácia (LOW_GRAVITY).
 */
public final class LowGravityEvent implements ChaosEvent {

    public static final String ID = "LOW_GRAVITY";

    private String name = "Nízka gravitácia";
    private String description = "Všetci hráči v aréne pocítia mesačnú gravitáciu.";
    private int durationSeconds = 18;
    private int cooldownSeconds = 30;
    private boolean enabled = true;
    private double gravityMultiplier = 0.35;

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

    public double getGravityMultiplier() {
        return gravityMultiplier;
    }

    public void setGravityMultiplier(double gravityMultiplier) {
        this.gravityMultiplier = Math.clamp(gravityMultiplier, 0.05, 2.0);
    }

    @Override
    public void onActivate(Arena arena, ChaosEventContext context) {
        if (!context.isServerRuntimeAvailable() || context.getLowGravityModifierKey() == null) {
            return;
        }
        NamespacedKey key = context.getLowGravityModifierKey();
        double scalarDelta = gravityMultiplier - 1.0;
        AttributeModifier modifier = new AttributeModifier(
                key,
                scalarDelta,
                AttributeModifier.Operation.MULTIPLY_SCALAR_1,
                EquipmentSlotGroup.ANY
        );
        for (UUID uuid : arena.getAlivePlayers()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                AttributeInstance gravity = player.getAttribute(Attribute.GRAVITY);
                if (gravity != null) {
                    gravity.removeModifier(key);
                    gravity.addTransientModifier(modifier);
                }
            }
        }
    }

    @Override
    public void onDeactivate(Arena arena, ChaosEventContext context) {
        if (!context.isServerRuntimeAvailable() || context.getLowGravityModifierKey() == null) {
            return;
        }
        NamespacedKey key = context.getLowGravityModifierKey();
        for (UUID uuid : arena.getPlayers()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                AttributeInstance gravity = player.getAttribute(Attribute.GRAVITY);
                if (gravity != null) {
                    gravity.removeModifier(key);
                }
                player.setFallDistance(0.0f);
            }
        }
    }
}
