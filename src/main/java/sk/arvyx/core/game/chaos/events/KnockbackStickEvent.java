package sk.arvyx.core.game.chaos.events;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import sk.arvyx.core.arena.Arena;
import sk.arvyx.core.game.chaos.ChaosEvent;
import sk.arvyx.core.game.chaos.ChaosEventContext;

import java.util.UUID;

/**
 * Chaos udalosť: Odhadzovacia palica (KNOCKBACK_STICK).
 */
public final class KnockbackStickEvent implements ChaosEvent {

    public static final String ID = "KNOCKBACK_STICK";

    private String name = "Odhadzovacia palica";
    private String description = "Každý hráč získa dočasnú palicu s extrémnym odhodením.";
    private int durationSeconds = 20;
    private int cooldownSeconds = 35;
    private boolean enabled = true;
    private int knockbackLevel = 4;

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

    public int getKnockbackLevel() {
        return knockbackLevel;
    }

    public void setKnockbackLevel(int knockbackLevel) {
        this.knockbackLevel = Math.clamp(knockbackLevel, 1, 10);
    }

    @Override
    public void onActivate(Arena arena, ChaosEventContext context) {
        if (!context.isServerRuntimeAvailable()) {
            return;
        }
        ItemStack stick = new ItemStack(Material.STICK);
        ItemMeta meta = stick.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("Chaos Odhadzovacia Palica", NamedTextColor.LIGHT_PURPLE));
            meta.addEnchant(Enchantment.KNOCKBACK, knockbackLevel, true);
            NamespacedKey key = context.getKnockbackStickKey();
            if (key != null) {
                meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
            }
            stick.setItemMeta(meta);
        }

        for (UUID uuid : arena.getAlivePlayers()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                player.getInventory().addItem(stick.clone());
            }
        }
    }

    @Override
    public void onDeactivate(Arena arena, ChaosEventContext context) {
        if (!context.isServerRuntimeAvailable()) {
            return;
        }
        NamespacedKey key = context.getKnockbackStickKey();
        if (key == null) {
            return;
        }
        for (UUID uuid : arena.getPlayers()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                ItemStack[] contents = player.getInventory().getContents();
                for (int i = 0; i < contents.length; i++) {
                    ItemStack item = contents[i];
                    if (item != null && item.hasItemMeta()
                            && item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE)) {
                        player.getInventory().setItem(i, null);
                    }
                }
            }
        }
    }
}
