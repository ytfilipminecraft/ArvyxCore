package sk.arvyx.core.game.chaos;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.java.JavaPlugin;
import sk.arvyx.core.util.Logger;
import sk.arvyx.core.util.MessageFormatter;

import java.util.Objects;

/**
 * Kontext poskytovaný Chaos udalostiam pri aktivácii, tikoch a deaktivácii.
 */
public final class ChaosEventContext {

    private final JavaPlugin plugin;
    private final Logger logger;
    private final MessageFormatter messageFormatter;
    private final NamespacedKey knockbackStickKey;
    private final NamespacedKey lowGravityModifierKey;

    public ChaosEventContext(JavaPlugin plugin, Logger logger, MessageFormatter messageFormatter) {
        this.plugin = plugin;
        this.logger = Objects.requireNonNull(logger, "Logger nesmie byť null");
        this.messageFormatter = messageFormatter;
        this.knockbackStickKey = plugin != null ? new NamespacedKey(plugin, "chaos_knockback_stick") : null;
        this.lowGravityModifierKey = plugin != null ? new NamespacedKey(plugin, "chaos_low_gravity") : null;
    }

    public static ChaosEventContext headless(Logger logger) {
        return new ChaosEventContext(null, logger, null);
    }

    public JavaPlugin getPlugin() {
        return plugin;
    }

    public boolean isServerRuntimeAvailable() {
        return plugin != null && plugin.isEnabled();
    }

    public Logger getLogger() {
        return logger;
    }

    public MessageFormatter getMessageFormatter() {
        return messageFormatter;
    }

    public NamespacedKey getKnockbackStickKey() {
        return knockbackStickKey;
    }

    public NamespacedKey getLowGravityModifierKey() {
        return lowGravityModifierKey;
    }
}
