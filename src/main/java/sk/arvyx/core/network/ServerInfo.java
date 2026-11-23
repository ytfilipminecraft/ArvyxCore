package sk.arvyx.core.network;

import java.time.Instant;
import java.util.Objects;

/**
 * Identifikačný a stavový záznam servera v sieti ARVYX NETWORK.
 */
public record ServerInfo(
        String serverId,
        String role,
        String displayName,
        int onlinePlayers,
        Instant lastHeartbeat,
        boolean online
) {
    public ServerInfo {
        if (serverId == null || serverId.isBlank()) {
            throw new IllegalArgumentException("serverId nesmie byť prázdne");
        }
        Objects.requireNonNull(role, "role nesmie byť null");
        Objects.requireNonNull(displayName, "displayName nesmie byť null");
        Objects.requireNonNull(lastHeartbeat, "lastHeartbeat nesmie byť null");
    }
}
