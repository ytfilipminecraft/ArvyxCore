package sk.arvyx.core.matchmaking;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import sk.arvyx.core.arena.Arena;
import sk.arvyx.core.arena.ArenaManager;
import sk.arvyx.core.arena.ArenaState;
import sk.arvyx.core.arena.ArenaType;
import sk.arvyx.core.config.ConfigManager;
import sk.arvyx.core.countdown.CountdownManager;
import sk.arvyx.core.game.GameManager;
import sk.arvyx.core.network.ArenaStatusSnapshot;
import sk.arvyx.core.network.NetworkManager;
import sk.arvyx.core.player.PlayerManager;
import sk.arvyx.core.util.Logger;
import sk.arvyx.core.util.MessageFormatter;

import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Systém matchmakingu pre výber voľnej arény podľa typu hry, pripojenie a bezpečné odpojenie hráčov.
 */
public final class MatchmakingManager {

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

    private final String localServerId;
    private final ArenaManager arenaManager;
    private final PlayerManager playerManager;
    private final CountdownManager countdownManager;
    private final GameManager gameManager;
    private final NetworkManager networkManager;
    private final Logger logger;

    private ConfigManager configManager;
    private MessageFormatter messageFormatter;

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
    }

    /**
     * Vyberie najlepšiu dostupnú lokálnu arénu podľa typu hry.
     * Uprednostní arénu, v ktorej už čakajú hráči (rýchlejšie naplnenie zápasu).
     */
    public Optional<Arena> selectBestLocalArena(ArenaType type) {
        if (type == null) {
            return Optional.empty();
        }
        return arenaManager.getArenasByType(type).stream()
                .filter(arena -> arena.getState().isJoinable())
                .filter(arena -> !arena.isFull())
                .max(Comparator
                        .comparingInt((Arena a) -> a.getState() == ArenaState.STARTING ? 1 : 0)
                        .thenComparingInt(Arena::getCurrentPlayerCount)
                        .thenComparing(Arena::getId, Comparator.reverseOrder()));
    }

    /**
     * Vyberie najlepšiu dostupnú vzdialenú arénu zo synchronizovaných snímok online serverov.
     */
    public Optional<ArenaStatusSnapshot> selectBestRemoteArena(ArenaType type) {
        if (type == null) {
            return Optional.empty();
        }
        return networkManager.getJoinableRemoteArenas(type).stream()
                .max(Comparator
                        .comparingInt((ArenaStatusSnapshot s) -> s.state() == ArenaState.STARTING ? 1 : 0)
                        .thenComparingInt(ArenaStatusSnapshot::currentPlayers)
                        .thenComparing(ArenaStatusSnapshot::arenaId, Comparator.reverseOrder()));
    }

    /**
     * Čistá logika priradenia hráča do lokálnej arény vrátane automatického spustenia odpočítavania.
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

        Arena arena = arenaOpt.get();
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

        // 1. Pokus o pripojenie do lokálnej arény na tomto serveri
        MatchmakingResult localResult = assignToBestLocalArena(uuid, type);
        if (localResult.status() == MatchmakingResult.Status.JOINED_LOCAL && localResult.localArena() != null) {
            Arena arena = localResult.localArena();
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
            if (localResult.countdownStarted()) {
                sendMsg(player, "matchmaking.auto-countdown-started", Map.of());
            }
            return localResult;
        }

        // 2. Vyhľadanie voľnej arény na vzdialenom backend serveri cez Velocity
        Optional<ArenaStatusSnapshot> remoteOpt = selectBestRemoteArena(type);
        if (remoteOpt.isPresent()) {
            ArenaStatusSnapshot remote = remoteOpt.get();
            sendMsg(player, "matchmaking.transferring-remote", Map.of(
                    "arena", remote.arenaId(),
                    "server", remote.serverId()
            ));
            if (playerManager.transferToServer(player, remote.serverId())) {
                return new MatchmakingResult(MatchmakingResult.Status.TRANSFERRED_REMOTE, null, remote, false);
            }
        }

        sendMsg(player, "matchmaking.no-arena-available", Map.of("type", type.getDisplayName()));
        return new MatchmakingResult(MatchmakingResult.Status.NO_ARENA_AVAILABLE, null, null, false);
    }

    /**
     * Bezpečné opustenie aktuálnej arény hráčom a návrat na lobby.
     */
    public boolean leavePlayer(Player player) {
        Objects.requireNonNull(player, "player nesmie byť null");
        UUID uuid = player.getUniqueId();

        Optional<Arena> arenaOpt = playerManager.removePlayerFromArena(uuid);
        if (arenaOpt.isEmpty()) {
            sendMsg(player, "matchmaking.not-in-arena", Map.of());
            return false;
        }

        Arena arena = arenaOpt.get();
        if (arena.getState() == ArenaState.INGAME) {
            gameManager.handlePlayerQuit(arena, uuid);
        } else if (arena.getState() == ArenaState.STARTING && !arena.hasMinimumPlayers()) {
            countdownManager.cancelCountdown(arena.getId(), true);
        }

        networkManager.publishArenaStatus(ArenaStatusSnapshot.fromArena(localServerId, arena));
        sendMsg(player, "arena.leave-success", Map.of("arena", arena.getName()));
        playerManager.returnToLobby(player);
        return true;
    }

    private void sendMsg(Player player, String key, Map<String, String> placeholders) {
        if (configManager != null && messageFormatter != null) {
            messageFormatter.send(player, configManager.getMessage(key), placeholders);
        }
    }
}
