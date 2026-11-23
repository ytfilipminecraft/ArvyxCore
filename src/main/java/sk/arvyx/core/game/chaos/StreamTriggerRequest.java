package sk.arvyx.core.game.chaos;

import java.time.Instant;
import java.util.Objects;

/**
 * Štruktúrovaná požiadavka na vyvolanie Chaos udalosti z budúcej streamovacej integrácie.
 */
public record StreamTriggerRequest(
        String platform,
        String requesterName,
        String arenaId,
        String eventId,
        Instant requestedAt
) {
    public StreamTriggerRequest {
        if (platform == null || platform.isBlank()) {
            throw new IllegalArgumentException("platform nesmie byť prázdne");
        }
        if (arenaId == null || arenaId.isBlank()) {
            throw new IllegalArgumentException("arenaId nesmie byť prázdne");
        }
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId nesmie byť prázdne");
        }
        Objects.requireNonNull(requestedAt, "requestedAt nesmie byť null");
    }
}
