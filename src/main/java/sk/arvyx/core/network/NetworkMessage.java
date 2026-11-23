package sk.arvyx.core.network;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Nemenná dátová správa pripravená pre lokálny prenos aj budúcu serializáciu cez Redis/API.
 */
public final class NetworkMessage {

    private final UUID messageId;
    private final NetworkMessageType type;
    private final String sourceServerId;
    private final String targetServerId;
    private final Instant timestamp;
    private final Map<String, String> payload;

    public NetworkMessage(
            UUID messageId,
            NetworkMessageType type,
            String sourceServerId,
            String targetServerId,
            Instant timestamp,
            Map<String, String> payload
    ) {
        this.messageId = Objects.requireNonNull(messageId, "messageId nesmie byť null");
        this.type = Objects.requireNonNull(type, "type nesmie byť null");
        if (sourceServerId == null || sourceServerId.isBlank()) {
            throw new IllegalArgumentException("sourceServerId nesmie byť prázdne");
        }
        this.sourceServerId = sourceServerId.trim();
        this.targetServerId = (targetServerId == null || targetServerId.isBlank()) ? "*" : targetServerId.trim();
        this.timestamp = Objects.requireNonNull(timestamp, "timestamp nesmie byť null");
        this.payload = payload == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }

    public static NetworkMessage broadcast(NetworkMessageType type, String sourceServerId, Map<String, String> payload) {
        return new NetworkMessage(UUID.randomUUID(), type, sourceServerId, "*", Instant.now(), payload);
    }

    public static NetworkMessage direct(
            NetworkMessageType type,
            String sourceServerId,
            String targetServerId,
            Map<String, String> payload
    ) {
        return new NetworkMessage(UUID.randomUUID(), type, sourceServerId, targetServerId, Instant.now(), payload);
    }

    public UUID getMessageId() {
        return messageId;
    }

    public NetworkMessageType getType() {
        return type;
    }

    public String getSourceServerId() {
        return sourceServerId;
    }

    public String getTargetServerId() {
        return targetServerId;
    }

    public boolean isBroadcast() {
        return "*".equals(targetServerId);
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public Map<String, String> getPayload() {
        return payload;
    }

    public String getPayloadValue(String key) {
        return payload.get(key);
    }
}
