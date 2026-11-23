package sk.arvyx.core.matchmaking;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import sk.arvyx.core.arena.Arena;
import sk.arvyx.core.arena.ArenaManager;
import sk.arvyx.core.arena.ArenaState;
import sk.arvyx.core.arena.ArenaType;
import sk.arvyx.core.config.ConfigManager;
import sk.arvyx.core.countdown.CountdownManager;
import sk.arvyx.core.game.GameManager;
import sk.arvyx.core.network.ArenaStatusSnapshot;
import sk.arvyx.core.network.NetworkManager;
import sk.arvyx.core.network.NetworkMessage;
import sk.arvyx.core.network.NetworkMessageType;
import sk.arvyx.core.player.PlayerManager;
import sk.arvyx.core.util.Logger;
import sk.arvyx.core.util.MessageFormatter;

import java.time.Instant;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Systém matchmakingu pre výber voľnej arény, rezerváciu miest medzi servermi,
 * automatické pripojenie po Velocity prenose a bezpečné odpojenie hráčov.
 */
public final class MatchmakingManager {

    public static final long RESERVATION_TTL_SECONDS = 15L;

    public record MatchmakingResult(
            Status status,
            Arena localArena,
            ArenaStatusSnapshot remoteArena,
            boolean countdownStarted
    ) {
        public enum Status {
            JOINED_LOCAL,
            TRANSFERRED_REMOTE,
            ALREADY_IN_ARENA,
            NO_ARENA_AVAILABLE,
            FAILED
        }
    }

    private record OutboundReservation(UUID playerId, String requestId, String serverId, String arenaId, Instant expiresAt) {
    }

    private final String localServerId;
    private final ArenaManager arenaManager;
    private final PlayerManager playerManager;
    private final CountdownManager countdownManager;
    private final GameManager gameManager;
    private final NetworkManager networkManager;
    private final Logger logger;

    private final Map<UUID, OutboundReservation> outboundReservations = new ConcurrentHashMap<>();

    private ConfigManager configManager;
    private MessageFormatter messageFormatter;
    private Plugin plugin;

    public MatchmakingManager(
            String localServerId,
            ArenaManager arenaManager,
            PlayerManager playerManager,
            CountdownManager countdownManager,
            GameManager gameManager,
            NetworkManager networkManager,
            Logger logger
    ) {
        this.localServerId = (localServerId == null || localServerId.isBlank()) ? "standalone" : localServerId.trim();
        this.arenaManager = Objects.requireNonNull(arenaManager, "ArenaManager nesmie byť null");
        this.playerManager = Objects.requireNonNull(playerManager, "PlayerManager nesmie byť null");
        this.countdownManager = Objects.requireNonNull(countdownManager, "CountdownManager nesmie byť null");
        this.gameManager = Objects.requireNonNull(gameManager, "GameManager nesmie byť null");
        this.networkManager = Objects.requireNonNull(networkManager, "NetworkManager nesmie byť null");
        this.logger = Objects.requireNonNull(logger, "Logger nesmie byť null");
    }

    public void attachRuntime(ConfigManager configManager, MessageFormatter messageFormatter) {
        this.configManager = configManager;
        this.messageFormatter = messageFormatter;
        this.plugin = null;
    }

    public void attachRuntime(ConfigManager configManager, MessageFormatter messageFormatter, Plugin plugin) {
        this.configManager = configManager;
        this.messageFormatter = messageFormatter;
        this.plugin = plugin;
    }

    public Optional<Arena> selectBestLocalArena(ArenaType type) {
        if (type == null) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        return arenaManager.getArenasByType(type).stream()
                .filter(arena -> arena.getState().isJoinable())
                .filter(arena -> !arena.isFullIncludingReservations(now))
                .max(Comparator
                        .comparingInt((Arena a) -> a.getState() == ArenaState.STARTING ? 1 : 0)
                        .thenComparingInt(a -> a.getEffectivePlayerCount(now))
                        .thenComparing(Arena::getId, Comparator.reverseOrder()));
    }

    public Optional<ArenaStatusSnapshot> selectBestRemoteArena(ArenaType type) {
        if (type == null) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        return networkManager.getJoinableRemoteArenas(type).stream()
                .filter(snapshot -> {
                    int pending = countOutboundFor(snapshot.serverId(), snapshot.arenaId());
                    return (snapshot.currentPlayers() + pending) < snapshot.maxPlayers();
                })
                .max(Comparator
                        .comparingInt((ArenaStatusSnapshot s) -> s.state() == ArenaState.STARTING ? 1 : 0)
                        .thenComparingInt(s -> s.currentPlayers() + countOutboundFor(s.serverId(), s.arenaId()))
                        .thenComparing(ArenaStatusSnapshot::arenaId, Comparator.reverseOrder()));
    }

    /**
     * Priradí hráča do najlepšej dostupnej lokálnej arény (vrátane prípadnej rezervácie).
     */
    public synchronized MatchmakingResult assignToBestLocalArena(UUID playerId, ArenaType type) {
        Objects.requireNonNull(playerId, "playerId nesmie byť null");
        Objects.requireNonNull(type, "type nesmie byť null");

        if (playerManager.isInArena(playerId)) {
            return new MatchmakingResult(MatchmakingResult.Status.ALREADY_IN_ARENA, null, null, false);
        }

        Optional<Arena> arenaOpt = selectBestLocalArena(type);
        if (arenaOpt.isEmpty()) {
            return new MatchmakingResult(MatchmakingResult.Status.NO_ARENA_AVAILABLE, null, null, false);
        }
        return assignToSpecificLocalArena(playerId, arenaOpt.get());
    }

    public synchronized MatchmakingResult assignToSpecificLocalArena(UUID playerId, Arena arena) {
        Objects.requireNonNull(playerId, "playerId nesmie byť null");
        Objects.requireNonNull(arena, "arena nesmie byť null");

        if (playerManager.isInArena(playerId)) {
            return new MatchmakingResult(MatchmakingResult.Status.ALREADY_IN_ARENA, null, null, false);
        }

        PlayerManager.JoinResult joinResult = playerManager.assignPlayerToArena(playerId, arena);
        if (joinResult == PlayerManager.JoinResult.ALREADY_IN_OTHER_ARENA
                || joinResult == PlayerManager.JoinResult.ALREADY_IN_THIS_ARENA) {
            return new MatchmakingResult(MatchmakingResult.Status.ALREADY_IN_ARENA, null, null, false);
        }
        if (joinResult != PlayerManager.JoinResult.SUCCESS) {
            return new MatchmakingResult(MatchmakingResult.Status.FAILED, null, null, false);
        }

        boolean countdownStarted = false;
        if (arena.getState() == ArenaState.WAITING && arena.hasMinimumPlayers()) {
            countdownStarted = countdownManager.startCountdown(arena, gameManager::startGame);
        }

        networkManager.publishArenaStatus(ArenaStatusSnapshot.fromArena(localServerId, arena));
        return new MatchmakingResult(MatchmakingResult.Status.JOINED_LOCAL, arena, null, countdownStarted);
    }

    /**
     * Spracuje prichádzajúcu požiadavku na rezerváciu miesta z iného servera cez Redis.
     */
    public synchronized boolean handleIncomingReservationRequest(NetworkMessage message) {
        if (message == null || message.getType() != NetworkMessageType.PLAYER_TRANSFER_REQUEST) {
            return false;
        }
        String rawUuid = message.getPayloadValue("playerUuid");
        String arenaId = message.getPayloadValue("arenaId");
        String rawExpires = message.getPayloadValue("expiresAtEpochMs");
        if (rawUuid == null || arenaId == null) {
            return false;
        }

        UUID playerUuid;
        Instant expiresAt;
        try {
            playerUuid = UUID.fromString(rawUuid);
            long expMs = rawExpires != null
                    ? Long.parseLong(rawExpires)
                    : Instant.now().plusSeconds(RESERVATION_TTL_SECONDS).toEpochMilli();
            expiresAt = Instant.ofEpochMilli(expMs);
        } catch (IllegalArgumentException ex) {
            return false;
        }

        String requestId = message.getPayloadValue("requestId");
        if (requestId == null || requestId.isBlank()
                || !message.getSourceServerId().matches("[a-zA-Z0-9_-]+")
                || !expiresAt.isAfter(Instant.now())) {
            return false;
        }

        Optional<Arena> arenaOpt = arenaManager.getArena(arenaId);
        boolean reserved = arenaOpt.isPresent()
                && arenaOpt.get().reserveSlot(playerUuid, expiresAt, Instant.now());
        if (reserved) {
            Arena arena = arenaOpt.orElseThrow();
            logger.debug("Rezervované miesto v aréne '" + arena.getId() + "' pre hráča " + playerUuid);
            networkManager.publishArenaStatus(ArenaStatusSnapshot.fromArena(localServerId, arena));
        }
        networkManager.sendMessage(NetworkMessage.direct(
                NetworkMessageType.PLAYER_TRANSFER_ACK,
                localServerId,
                message.getSourceServerId(),
                Map.of(
                        "requestId", requestId,
                        "playerUuid", playerUuid.toString(),
                        "arenaId", arenaId,
                        "accepted", String.valueOf(reserved)
                )
        ));
        return reserved;
    }

    /**
     * Po pripojení hráča na backend server skontroluje, či má aktívnu rezerváciu a rovno ho zaradí do arény.
     */
    public synchronized boolean claimReservationOnJoin(Player player) {
        if (player == null || !player.isOnline()) {
            return false;
        }
        UUID uuid = player.getUniqueId();
        Instant now = Instant.now();
        for (Arena arena : arenaManager.getAllArenas()) {
            if (arena.hasValidReservation(uuid, now)) {
                MatchmakingResult result = assignToSpecificLocalArena(uuid, arena);
                if (result.status() == MatchmakingResult.Status.JOINED_LOCAL) {
                    placeJoinedPlayerInArenaLobby(player, arena, result.countdownStarted());
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Rezervuje vzdialenú arénu (čisto dátová časť testovateľná bez Velocity/Bukkit hráča).
     */
    public synchronized Optional<ArenaStatusSnapshot> reserveRemoteTransfer(UUID playerId, ArenaType type, Instant now) {
        Objects.requireNonNull(playerId, "playerId nesmie byť null");
        Objects.requireNonNull(type, "type nesmie byť null");
        Objects.requireNonNull(now, "now nesmie byť null");

        if (playerManager.isInArena(playerId) || outboundReservations.containsKey(playerId)) {
            return Optional.empty();
        }
        Optional<ArenaStatusSnapshot> remoteOpt = selectBestRemoteArena(type);
        if (remoteOpt.isEmpty()) {
            return Optional.empty();
        }
        ArenaStatusSnapshot remote = remoteOpt.get();
        Instant expiresAt = now.plusSeconds(RESERVATION_TTL_SECONDS);
        String requestId = UUID.randomUUID().toString();
        outboundReservations.put(playerId, new OutboundReservation(
                playerId, requestId, remote.serverId(), remote.arenaId(), expiresAt
        ));

        networkManager.sendMessage(NetworkMessage.direct(
                NetworkMessageType.PLAYER_TRANSFER_REQUEST,
                localServerId,
                remote.serverId(),
                Map.of(
                        "requestId", requestId,
                        "playerUuid", playerId.toString(),
                        "arenaId", remote.arenaId(),
                        "gameType", type.name(),
                        "expiresAtEpochMs", String.valueOf(expiresAt.toEpochMilli())
                )
        ));
        return Optional.of(remote);
    }

    public void cancelOutboundReservation(UUID playerId) {
        if (playerId != null) {
            outboundReservations.remove(playerId);
        }
    }

    /**
     * Spracuje ACK z cieľového backendu. Túto metódu musí volať hlavné Bukkit vlákno.
     */
    public boolean handleTransferAcknowledgement(NetworkMessage message) {
        if (message == null || message.getType() != NetworkMessageType.PLAYER_TRANSFER_ACK) {
            return false;
        }
        String rawUuid = message.getPayloadValue("playerUuid");
        String requestId = message.getPayloadValue("requestId");
        String acceptedValue = message.getPayloadValue("accepted");
        if (rawUuid == null || requestId == null || acceptedValue == null) {
            return false;
        }

        UUID playerId;
        try {
            playerId = UUID.fromString(rawUuid);
        } catch (IllegalArgumentException ex) {
            return false;
        }

        OutboundReservation pending = outboundReservations.get(playerId);
        if (pending == null
                || !pending.requestId().equals(requestId)
                || !pending.serverId().equalsIgnoreCase(message.getSourceServerId())
                || !localServerId.equalsIgnoreCase(message.getTargetServerId())
                || !pending.arenaId().equals(message.getPayloadValue("arenaId"))) {
            return false;
        }
        if (!outboundReservations.remove(playerId, pending)) {
            return false;
        }

        Player player = Bukkit.getPlayer(playerId);
        if (!"true".equalsIgnoreCase(acceptedValue)
                || !pending.expiresAt().isAfter(Instant.now())
                || player == null || !player.isOnline()) {
            if (player != null && player.isOnline()) {
                sendMsg(player, "matchmaking.no-arena-available", Map.of("type", "požadovaná hra"));
            }
            return true;
        }

        if (!playerManager.transferToServer(player, pending.serverId())) {
            sendMsg(player, "matchmaking.no-arena-available", Map.of("type", "požadovaná hra"));
            logger.warn("Velocity presun hráča " + playerId + " zlyhal po potvrdenej rezervácii v "
                    + pending.serverId() + "; cieľová rezervácia vyprší automaticky.");
        }
        return true;
    }

    /**
     * Zruší čakajúce požiadavky po ich TTL; timeout nikdy nespustí presun hráča.
     */
    public int expirePendingTransfers(Instant now) {
        int expired = 0;
        for (Map.Entry<UUID, OutboundReservation> entry : outboundReservations.entrySet()) {
            OutboundReservation reservation = entry.getValue();
            if (!reservation.expiresAt().isAfter(now)
                    && outboundReservations.remove(entry.getKey(), reservation)) {
                expired++;
                if (plugin != null && plugin.isEnabled()) {
                    Player player = Bukkit.getPlayer(entry.getKey());
                    if (player != null && player.isOnline()) {
                        sendMsg(player, "matchmaking.no-arena-available", Map.of("type", "požadovaná hra"));
                    }
                }
            }
        }
        return expired;
    }

    /**
     * Kompletné pripojenie hráča v prostredí Paper (lokálna aréna alebo Velocity presun na vzdialený backend).
     */
    public MatchmakingResult joinPlayer(Player player, ArenaType type) {
        Objects.requireNonNull(player, "player nesmie byť null");
        Objects.requireNonNull(type, "type nesmie byť null");

        UUID uuid = player.getUniqueId();
        playerManager.handleJoin(uuid, player.getName());

        Optional<String> existingArena = playerManager.getCurrentArenaId(uuid);
        if (existingArena.isPresent()) {
            sendMsg(player, "arena.already-in-arena", Map.of("arena", existingArena.get()));
            return new MatchmakingResult(MatchmakingResult.Status.ALREADY_IN_ARENA, null, null, false);
        }

        // 1. Lokálna aréna
        MatchmakingResult localResult = assignToBestLocalArena(uuid, type);
        if (localResult.status() == MatchmakingResult.Status.JOINED_LOCAL && localResult.localArena() != null) {
            placeJoinedPlayerInArenaLobby(player, localResult.localArena(), localResult.countdownStarted());
            return localResult;
        }

        // 2. Vzdialená aréna cez Redis rezerváciu + Velocity presun
        Optional<ArenaStatusSnapshot> reservedRemote = reserveRemoteTransfer(uuid, type, Instant.now());
        if (reservedRemote.isPresent()) {
            ArenaStatusSnapshot remote = reservedRemote.get();
            sendMsg(player, "matchmaking.transferring-remote", Map.of(
                    "arena", remote.arenaId(),
                    "server", remote.serverId()
            ));
            // Presun sa vykoná až po PLAYER_TRANSFER_ACK z cieľového backendu.
            return new MatchmakingResult(MatchmakingResult.Status.TRANSFERRED_REMOTE, null, remote, false);
        }

        sendMsg(player, "matchmaking.no-arena-available", Map.of("type", type.getDisplayName()));
        return new MatchmakingResult(MatchmakingResult.Status.NO_ARENA_AVAILABLE, null, null, false);
    }

    private void placeJoinedPlayerInArenaLobby(Player player, Arena arena, boolean countdownStarted) {
        playerManager.restoreCleanPlayerState(player);
        player.setGameMode(GameMode.ADVENTURE);
        arena.getLobbySpawn().toBukkitLocation().ifPresentOrElse(
                player::teleport,
                () -> logger.warn("Svet '" + arena.getWorldName() + "' pre arénu '" + arena.getId()
                        + "' nie je načítaný — teleport do lobby arény preskočený.")
        );

        sendMsg(player, "arena.join-success", Map.of(
                "arena", arena.getName(),
                "players", String.valueOf(arena.getCurrentPlayerCount()),
                "max", String.valueOf(arena.getMaxPlayers())
        ));
        if (countdownStarted) {
            sendMsg(player, "matchmaking.auto-countdown-started", Map.of());
        }
    }

    /**
     * Bezpečné opustenie aktuálnej arény hráčom a návrat na lobby.
     */
    public boolean leavePlayer(Player player) {
        Objects.requireNonNull(player, "player nesmie byť null");
        UUID uuid = player.getUniqueId();

        Optional<Arena> currentOpt = arenaManager.findArenaByPlayer(uuid);
        if (currentOpt.isEmpty()) {
            sendMsg(player, "matchmaking.not-in-arena", Map.of());
            return false;
        }

        Arena arena = currentOpt.get();
        ArenaState stateBeforeRemove = arena.getState();
        boolean wasAlive = arena.isPlayerAlive(uuid);

        playerManager.removePlayerFromArena(uuid);

        if (stateBeforeRemove == ArenaState.INGAME && wasAlive) {
            gameManager.handlePlayerQuit(arena, uuid);
        } else if (stateBeforeRemove == ArenaState.STARTING && !arena.hasMinimumPlayers()) {
            countdownManager.cancelCountdown(arena.getId(), true);
        }

        networkManager.publishArenaStatus(ArenaStatusSnapshot.fromArena(localServerId, arena));
        sendMsg(player, "arena.leave-success", Map.of("arena", arena.getName()));
        playerManager.returnToLobby(player);
        return true;
    }

    public int cleanupExpiredReservations(Instant now) {
        Objects.requireNonNull(now, "now nesmie byť null");
        int removed = expirePendingTransfers(now);
        removed += pruneExpiredOutboundReservations(now);
        for (Arena arena : arenaManager.getAllArenas()) {
            removed += arena.pruneExpiredReservations(now);
        }
        return removed;
    }

    private int pruneExpiredOutboundReservations(Instant now) {
        int before = outboundReservations.size();
        outboundReservations.entrySet().removeIf(e -> !e.getValue().expiresAt().isAfter(now));
        return before - outboundReservations.size();
    }

    private int countOutboundFor(String serverId, String arenaId) {
        String s = serverId.toLowerCase(Locale.ROOT);
        String a = arenaId.toLowerCase(Locale.ROOT);
        int count = 0;
        for (OutboundReservation res : outboundReservations.values()) {
            if (res.serverId().toLowerCase(Locale.ROOT).equals(s)
                    && res.arenaId().toLowerCase(Locale.ROOT).equals(a)) {
                count++;
            }
        }
        return count;
    }

    private void sendMsg(Player player, String key, Map<String, String> placeholders) {
        if (configManager != null && messageFormatter != null) {
            messageFormatter.send(player, configManager.getMessage(key), placeholders);
        }
    }
}
