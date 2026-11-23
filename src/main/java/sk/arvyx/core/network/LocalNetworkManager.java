package sk.arvyx.core.network;

import sk.arvyx.core.arena.ArenaType;
import sk.arvyx.core.util.Logger;

import java.time.Duration;
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
 * Lokálna implementácia rozhrania NetworkManager bez Redis komunikácie.
 * Slúži pre samostatný beh servera aj ako vnútorný stavový register pri výpadku Redis.
 */
public final class LocalNetworkManager implements NetworkManager {

    private final Logger logger;
    private final String serverId;
    private final String serverRole;
    private final String displayName;
    private final int serverTimeoutSeconds;

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
            int serverTimeoutSeconds
    ) {
        this.logger = Objects.requireNonNull(logger, "Logger nesmie byť null");
        this.serverId = (serverId == null || serverId.isBlank()) ? "standalone" : serverId.trim();
        this.serverRole = (serverRole == null || serverRole.isBlank()) ? "STANDALONE" : serverRole.trim().toUpperCase(Locale.ROOT);
        this.displayName = (displayName == null || displayName.isBlank()) ? this.serverId : displayName.trim();
        this.serverTimeoutSeconds = Math.max(10, serverTimeoutSeconds);
        this.localServerInfo = new ServerInfo(this.serverId, this.serverRole, this.displayName, 0, Instant.now(), false);
    }

    public LocalNetworkManager(
            Logger logger,
            String serverId,
            String serverRole,
            String displayName,
            boolean ignoredRedisFlag
    ) {
        this(logger, serverId, serverRole, displayName, 30);
    }

    @Override
    public void initialize() {
        this.localServerInfo = new ServerInfo(serverId, serverRole, displayName, 0, Instant.now(), true);
        registerServer(localServerInfo);
        this.initialized = true;
        logger.info("NetworkManager inicializovaný v lokálnom režime (server=" + serverId + ", rola=" + serverRole + ").");
    }

    @Override
    public void shutdown() {
        if (!initialized) {
            return;
        }
        this.localServerInfo = new ServerInfo(serverId, serverRole, displayName, 0, Instant.now(), false);
        registeredServers.put(normalizeKey(serverId), localServerInfo);
        this.initialized = false;
        logger.info("Lokálny NetworkManager bol bezpečne ukončený.");
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
        if (!serverInfo.online()) {
            removeSnapshotsForServer(serverInfo.serverId());
        }
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
    public boolean isServerOnline(String targetServerId) {
        if (targetServerId == null || targetServerId.isBlank()) {
            return false;
        }
        return getServer(targetServerId).map(ServerInfo::online).orElse(false);
    }

    @Override
    public int detectUnavailableServers(Instant now) {
        Objects.requireNonNull(now, "now nesmie byť null");
        int markedOffline = 0;
        for (Map.Entry<String, ServerInfo> entry : registeredServers.entrySet()) {
            ServerInfo info = entry.getValue();
            if (info.serverId().equalsIgnoreCase(serverId) || !info.online()) {
                continue;
            }
            long elapsedSeconds = Duration.between(info.lastHeartbeat(), now).toSeconds();
            if (elapsedSeconds >= serverTimeoutSeconds) {
                ServerInfo offline = new ServerInfo(
                        info.serverId(),
                        info.role(),
                        info.displayName(),
                        0,
                        info.lastHeartbeat(),
                        false
                );
                registeredServers.put(entry.getKey(), offline);
                removeSnapshotsForServer(info.serverId());
                markedOffline++;
                logger.warn("Server '" + info.serverId() + "' neodpovedá (" + elapsedSeconds + "s) — označený ako nedostupný.");
            }
        }
        return markedOffline;
    }

    @Override
    public void publishArenaStatus(ArenaStatusSnapshot snapshot) {
        recordArenaSnapshot(snapshot);
    }

    public void recordArenaSnapshot(ArenaStatusSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot nesmie byť null");
        String key = normalizeKey(snapshot.serverId() + ":" + snapshot.arenaId());
        arenaSnapshots.put(key, snapshot);
    }

    public void removeSnapshotsForServer(String targetServerId) {
        if (targetServerId == null || targetServerId.isBlank()) {
            return;
        }
        String prefix = normalizeKey(targetServerId) + ":";
        arenaSnapshots.keySet().removeIf(k -> k.startsWith(prefix));
    }

    @Override
    public Collection<ArenaStatusSnapshot> getArenaSnapshots() {
        return Collections.unmodifiableCollection(List.copyOf(arenaSnapshots.values()));
    }

    @Override
    public Collection<ArenaStatusSnapshot> getJoinableRemoteArenas(ArenaType type) {
        if (type == null) {
            return List.of();
        }
        return arenaSnapshots.values().stream()
                .filter(s -> !s.serverId().equalsIgnoreCase(serverId))
                .filter(s -> s.type() == type)
                .filter(s -> s.state().isJoinable())
                .filter(s -> s.currentPlayers() < s.maxPlayers())
                .filter(s -> isServerOnline(s.serverId()))
                .toList();
    }

    @Override
    public void sendMessage(NetworkMessage message) {
        dispatchLocally(message);
    }

    public void dispatchLocally(NetworkMessage message) {
        Objects.requireNonNull(message, "message nesmie byť null");
        if (!message.isBroadcast() && !message.getTargetServerId().equalsIgnoreCase(serverId)) {
            logger.debug("Lokálny režim: správa " + message.getType() + " pre vzdialený server '"
                    + message.getTargetServerId() + "' nebola odoslaná cez sieť.");
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

    @Override
    public boolean isRedisConnected() {
        return false;
    }

    @Override
    public NetworkTransportMode getTransportMode() {
        return NetworkTransportMode.LOCAL_ONLY;
    }

    private static String normalizeKey(String key) {
        return key.trim().toLowerCase(Locale.ROOT);
    }
}
