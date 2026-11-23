package sk.arvyx.core.network;

import sk.arvyx.core.util.Logger;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Lokálna implementácia rozhrania NetworkManager bez externej závislosti na Redis.
 * Slúži ako plnohodnotný základ a príprava na budúcu distribuovanú komunikáciu.
 */
public final class LocalNetworkManager implements NetworkManager {

    private final Logger logger;
    private final String serverId;
    private final String serverRole;
    private final String displayName;
    private final boolean redisConfigured;

    private final Map<String, ServerInfo> registeredServers = new ConcurrentHashMap<>();
    private final Map<String, ArenaStatusSnapshot> arenaSnapshots = new ConcurrentHashMap<>();
    private final Map<NetworkMessageType, List<Consumer<NetworkMessage>>> handlers =
            Collections.synchronizedMap(new EnumMap<>(NetworkMessageType.class));

    private volatile ServerInfo localServerInfo;
    private volatile boolean initialized;

    public LocalNetworkManager(
            Logger logger,
            String serverId,
            String serverRole,
            String displayName,
            boolean redisConfigured
    ) {
        this.logger = Objects.requireNonNull(logger, "Logger nesmie byť null");
        this.serverId = (serverId == null || serverId.isBlank()) ? "standalone" : serverId.trim();
        this.serverRole = (serverRole == null || serverRole.isBlank()) ? "STANDALONE" : serverRole.trim().toUpperCase(Locale.ROOT);
        this.displayName = (displayName == null || displayName.isBlank()) ? this.serverId : displayName.trim();
        this.redisConfigured = redisConfigured;
        this.localServerInfo = new ServerInfo(this.serverId, this.serverRole, this.displayName, 0, Instant.now(), false);
    }

    @Override
    public void initialize() {
        this.localServerInfo = new ServerInfo(serverId, serverRole, displayName, 0, Instant.now(), true);
        registerServer(localServerInfo);
        this.initialized = true;

        if (redisConfigured) {
            logger.warn("Redis integrácia je v konfigurácii povolená, ale v ArvyxCore 1.0.0 beží lokálny transportný režim.");
        } else {
            logger.info("NetworkManager spustený v lokálnom režime (server=" + serverId + ", rola=" + serverRole + ").");
        }

        sendMessage(NetworkMessage.broadcast(
                NetworkMessageType.SERVER_REGISTER,
                serverId,
                Map.of("role", serverRole, "displayName", displayName)
        ));
    }

    @Override
    public void shutdown() {
        if (!initialized) {
            return;
        }
        sendMessage(NetworkMessage.broadcast(
                NetworkMessageType.SERVER_SHUTDOWN,
                serverId,
                Map.of("role", serverRole)
        ));
        this.localServerInfo = new ServerInfo(serverId, serverRole, displayName, 0, Instant.now(), false);
        registeredServers.put(normalizeKey(serverId), localServerInfo);
        this.initialized = false;
        logger.info("NetworkManager bol bezpečne ukončený.");
    }

    @Override
    public ServerInfo getLocalServerInfo() {
        return localServerInfo;
    }

    @Override
    public void updateLocalHeartbeat(int onlinePlayers) {
        int safeCount = Math.max(0, onlinePlayers);
        this.localServerInfo = new ServerInfo(serverId, serverRole, displayName, safeCount, Instant.now(), true);
        registeredServers.put(normalizeKey(serverId), localServerInfo);
    }

    @Override
    public void registerServer(ServerInfo serverInfo) {
        Objects.requireNonNull(serverInfo, "serverInfo nesmie byť null");
        registeredServers.put(normalizeKey(serverInfo.serverId()), serverInfo);
    }

    @Override
    public Optional<ServerInfo> getServer(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(registeredServers.get(normalizeKey(id)));
    }

    @Override
    public Collection<ServerInfo> getRegisteredServers() {
        return Collections.unmodifiableCollection(List.copyOf(registeredServers.values()));
    }

    @Override
    public void publishArenaStatus(ArenaStatusSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot nesmie byť null");
        String key = normalizeKey(snapshot.serverId() + ":" + snapshot.arenaId());
        arenaSnapshots.put(key, snapshot);
        sendMessage(NetworkMessage.broadcast(
                NetworkMessageType.ARENA_STATUS_UPDATE,
                snapshot.serverId(),
                Map.of(
                        "arenaId", snapshot.arenaId(),
                        "type", snapshot.type().name(),
                        "state", snapshot.state().name(),
                        "players", String.valueOf(snapshot.currentPlayers()),
                        "maxPlayers", String.valueOf(snapshot.maxPlayers())
                )
        ));
    }

    @Override
    public Collection<ArenaStatusSnapshot> getArenaSnapshots() {
        return Collections.unmodifiableCollection(List.copyOf(arenaSnapshots.values()));
    }

    @Override
    public void sendMessage(NetworkMessage message) {
        Objects.requireNonNull(message, "message nesmie byť null");
        if (!message.isBroadcast() && !message.getTargetServerId().equalsIgnoreCase(serverId)) {
            logger.debug("Správa " + message.getType() + " smerovaná pre vzdialený server " + message.getTargetServerId());
            return;
        }
        List<Consumer<NetworkMessage>> typeHandlers = handlers.get(message.getType());
        if (typeHandlers != null) {
            for (Consumer<NetworkMessage> handler : typeHandlers) {
                try {
                    handler.accept(message);
                } catch (Exception ex) {
                    logger.error("Chyba pri spracovaní sieťovej správy " + message.getType(), ex);
                }
            }
        }
    }

    @Override
    public void registerHandler(NetworkMessageType type, Consumer<NetworkMessage> handler) {
        Objects.requireNonNull(type, "type nesmie byť null");
        Objects.requireNonNull(handler, "handler nesmie byť null");
        handlers.computeIfAbsent(type, ignored -> new CopyOnWriteArrayList<>()).add(handler);
    }

    @Override
    public boolean isRedisEnabled() {
        return false;
    }

    private static String normalizeKey(String key) {
        return key.trim().toLowerCase(Locale.ROOT);
    }
}
