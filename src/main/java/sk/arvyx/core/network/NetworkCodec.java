package sk.arvyx.core.network;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import sk.arvyx.core.arena.ArenaState;
import sk.arvyx.core.arena.ArenaType;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Bezpečný JSON kodek pre serializáciu sieťových správ (bez Java ObjectInputStream).
 */
public final class NetworkCodec {

    private static final Gson GSON = new GsonBuilder().create();

    private NetworkCodec() {
    }

    public static String encodeMessage(NetworkMessage message) {
        WireMessage wire = new WireMessage(
                message.getMessageId().toString(),
                message.getType().name(),
                message.getSourceServerId(),
                message.getTargetServerId(),
                message.getTimestamp().toEpochMilli(),
                message.getPayload()
        );
        return GSON.toJson(wire);
    }

    public static Optional<NetworkMessage> decodeMessage(String json) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            WireMessage wire = GSON.fromJson(json, WireMessage.class);
            if (wire == null || wire.type == null || wire.sourceServerId == null) {
                return Optional.empty();
            }
            UUID id = wire.messageId != null ? UUID.fromString(wire.messageId) : UUID.randomUUID();
            NetworkMessageType type = NetworkMessageType.valueOf(wire.type);
            Instant ts = Instant.ofEpochMilli(wire.timestampEpochMs);
            return Optional.of(new NetworkMessage(id, type, wire.sourceServerId, wire.targetServerId, ts, wire.payload));
        } catch (JsonSyntaxException | IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    public static String encodeServerInfo(ServerInfo info) {
        WireServer wire = new WireServer(
                info.serverId(),
                info.role(),
                info.displayName(),
                info.onlinePlayers(),
                info.lastHeartbeat().toEpochMilli(),
                info.online()
        );
        return GSON.toJson(wire);
    }

    public static Optional<ServerInfo> decodeServerInfo(String json) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            WireServer wire = GSON.fromJson(json, WireServer.class);
            if (wire == null || wire.serverId == null || wire.serverId.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new ServerInfo(
                    wire.serverId,
                    wire.role != null ? wire.role : "STANDALONE",
                    wire.displayName != null ? wire.displayName : wire.serverId,
                    Math.max(0, wire.onlinePlayers),
                    Instant.ofEpochMilli(wire.lastHeartbeatEpochMs),
                    wire.online
            ));
        } catch (JsonSyntaxException | IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    public static String encodeArenaSnapshot(ArenaStatusSnapshot snapshot) {
        WireArena wire = new WireArena(
                snapshot.arenaId(),
                snapshot.serverId(),
                snapshot.type().name(),
                snapshot.state().name(),
                snapshot.currentPlayers(),
                snapshot.maxPlayers(),
                snapshot.updatedAt().toEpochMilli()
        );
        return GSON.toJson(wire);
    }

    public static Optional<ArenaStatusSnapshot> decodeArenaSnapshot(String json) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            WireArena wire = GSON.fromJson(json, WireArena.class);
            if (wire == null || wire.arenaId == null || wire.serverId == null || wire.type == null || wire.state == null) {
                return Optional.empty();
            }
            return Optional.of(new ArenaStatusSnapshot(
                    wire.arenaId,
                    wire.serverId,
                    ArenaType.valueOf(wire.type),
                    ArenaState.valueOf(wire.state),
                    Math.max(0, wire.currentPlayers),
                    Math.max(1, wire.maxPlayers),
                    Instant.ofEpochMilli(wire.updatedAtEpochMs)
            ));
        } catch (JsonSyntaxException | IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private static final class WireMessage {
        String messageId;
        String type;
        String sourceServerId;
        String targetServerId;
        long timestampEpochMs;
        Map<String, String> payload;

        WireMessage(
                String messageId,
                String type,
                String sourceServerId,
                String targetServerId,
                long timestampEpochMs,
                Map<String, String> payload
        ) {
            this.messageId = messageId;
            this.type = type;
            this.sourceServerId = sourceServerId;
            this.targetServerId = targetServerId;
            this.timestampEpochMs = timestampEpochMs;
            this.payload = payload;
        }
    }

    private static final class WireServer {
        String serverId;
        String role;
        String displayName;
        int onlinePlayers;
        long lastHeartbeatEpochMs;
        boolean online;

        WireServer(
                String serverId,
                String role,
                String displayName,
                int onlinePlayers,
                long lastHeartbeatEpochMs,
                boolean online
        ) {
            this.serverId = serverId;
            this.role = role;
            this.displayName = displayName;
            this.onlinePlayers = onlinePlayers;
            this.lastHeartbeatEpochMs = lastHeartbeatEpochMs;
            this.online = online;
        }
    }

    private static final class WireArena {
        String arenaId;
        String serverId;
        String type;
        String state;
        int currentPlayers;
        int maxPlayers;
        long updatedAtEpochMs;

        WireArena(
                String arenaId,
                String serverId,
                String type,
                String state,
                int currentPlayers,
                int maxPlayers,
                long updatedAtEpochMs
        ) {
            this.arenaId = arenaId;
            this.serverId = serverId;
            this.type = type;
            this.state = state;
            this.currentPlayers = currentPlayers;
            this.maxPlayers = maxPlayers;
            this.updatedAtEpochMs = updatedAtEpochMs;
        }
    }
}
