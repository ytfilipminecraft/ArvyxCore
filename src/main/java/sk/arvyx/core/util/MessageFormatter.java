package sk.arvyx.core.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Formátovač správ využívajúci Adventure MiniMessage s jednotným dizajnom ARVYX NETWORK.
 */
public final class MessageFormatter {

    public static final String DEFAULT_PREFIX = "<gradient:#8A2BE2:#4169E1>[ARVYX]</gradient>";

    private final MiniMessage miniMessage;
    private volatile String prefix;

    public MessageFormatter() {
        this(DEFAULT_PREFIX);
    }

    public MessageFormatter(String prefix) {
        this.miniMessage = MiniMessage.miniMessage();
        this.prefix = (prefix == null || prefix.isBlank()) ? DEFAULT_PREFIX : prefix;
    }

    public void setPrefix(String prefix) {
        this.prefix = (prefix == null || prefix.isBlank()) ? DEFAULT_PREFIX : prefix;
    }

    public String getPrefix() {
        return prefix;
    }

    public Component format(String template) {
        return format(template, Map.of());
    }

    public Component format(String template, Map<String, String> placeholders) {
        if (template == null || template.isEmpty()) {
            return Component.empty();
        }
        String resolvedPrefix = prefix;
        String prepared = template.replace("<prefix>", resolvedPrefix);

        List<TagResolver> resolvers = new ArrayList<>();
        if (placeholders != null) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    resolvers.add(Placeholder.parsed(entry.getKey(), entry.getValue()));
                }
            }
        }
        return miniMessage.deserialize(prepared, TagResolver.resolver(resolvers));
    }

    public Component formatLiteral(String template, Map<String, String> placeholders) {
        if (template == null || template.isEmpty()) {
            return Component.empty();
        }
        String prepared = template.replace("<prefix>", prefix);
        List<TagResolver> resolvers = new ArrayList<>();
        if (placeholders != null) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    resolvers.add(Placeholder.unparsed(entry.getKey(), entry.getValue()));
                }
            }
        }
        return miniMessage.deserialize(prepared, TagResolver.resolver(resolvers));
    }

    public void send(CommandSender sender, String template) {
        if (sender != null && template != null && !template.isBlank()) {
            sender.sendMessage(format(template));
        }
    }

    public void send(CommandSender sender, String template, Map<String, String> placeholders) {
        if (sender != null && template != null && !template.isBlank()) {
            sender.sendMessage(format(template, placeholders));
        }
    }

    public void broadcast(Collection<? extends Player> players, String template, Map<String, String> placeholders) {
        Objects.requireNonNull(players, "Zoznam hráčov nesmie byť null");
        if (template == null || template.isBlank()) {
            return;
        }
        Component component = format(template, placeholders);
        for (Player player : players) {
            if (player != null && player.isOnline()) {
                player.sendMessage(component);
            }
        }
    }
}
