package sk.arvyx.core;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import sk.arvyx.core.arena.Arena;
import sk.arvyx.core.arena.ArenaManager;
import sk.arvyx.core.arena.ArenaState;
import sk.arvyx.core.command.ArvyxCommand;
import sk.arvyx.core.config.ConfigManager;
import sk.arvyx.core.countdown.CountdownManager;
import sk.arvyx.core.game.GameManager;
import sk.arvyx.core.game.chaos.ChaosModule;
import sk.arvyx.core.game.chaos.StreamTriggerRequest;
import sk.arvyx.core.game.skyrush.SkyRushModule;
import sk.arvyx.core.listener.ArenaBlockListener;
import sk.arvyx.core.listener.PlayerLifecycleListener;
import sk.arvyx.core.matchmaking.MatchmakingManager;
import sk.arvyx.core.network.ArenaStatusSnapshot;
import sk.arvyx.core.network.NetworkManager;
import sk.arvyx.core.network.NetworkMessageType;
import sk.arvyx.core.network.RedisConfig;
import sk.arvyx.core.network.RedisNetworkManager;
import sk.arvyx.core.player.PlayerManager;
import sk.arvyx.core.reset.ArenaBlockTracker;
import sk.arvyx.core.reset.ResetManager;
import sk.arvyx.core.reset.WorldBackupManager;
import sk.arvyx.core.util.Logger;
import sk.arvyx.core.util.MessageFormatter;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Hlavný koordinačný modul jadra ArvyxCore 1.1.0.
 * Prepája konfiguráciu, sieťovú Redis Pub/Sub vrstvu, matchmaking, arény, map reset a bezpečné vypnutie.
 */
public final class CoreModule {

    private final JavaPlugin plugin;
    private final Logger logger;

    private ConfigManager configManager;
    private MessageFormatter messageFormatter;
    private ArenaManager arenaManager;
    private PlayerManager playerManager;
    private NetworkManager networkManager;
    private CountdownManager countdownManager;
    private GameManager gameManager;
    private MatchmakingManager matchmakingManager;
    private ArenaBlockTracker blockTracker;
    private WorldBackupManager worldBackupManager;
    private ResetManager resetManager;
    private ArenaBlockListener arenaBlockListener;
    private BukkitTask heartbeatTask;

    public CoreModule(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "Plugin nesmie byť null");
        this.logger = new Logger(plugin.getLogger());
    }

    public void initialize() {
        logger.info("Inicializácia ArvyxCore v" + getPluginVersion() + " pre ARVYX NETWORK...");

        // 1. Konfigurácia a formátovanie správ
        this.configManager = new ConfigManager(plugin, logger);
        this.configManager.loadAll();
        this.messageFormatter = new MessageFormatter(configManager.getPrefix());

        // 2. Sieťová vrstva (Redis Pub/Sub s bezpečným fallbackom na lokálny režim)
        RedisConfig redisConfig = configManager.getRedisConfig();
        this.networkManager = new RedisNetworkManager(
                logger,
                configManager.getServerId(),
                configManager.getServerRole(),
                configManager.getServerDisplayName(),
                redisConfig
        );
        this.networkManager.initialize();

        // Registrácia BungeeCord/Velocity outgoing kanála pre bezpečný presun hráčov medzi servermi
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, PlayerManager.BUNGEECORD_CHANNEL);

        // 3. Správa arén (len arény priradené k tomuto server.id) a hráčov
        this.arenaManager = new ArenaManager(logger);
        loadArenasIntoManager();

        this.playerManager = new PlayerManager(arenaManager, logger);
        this.playerManager.attachRuntime(
                plugin,
                messageFormatter,
                configManager.getServerId(),
                configManager.getLobbyServer(),
                configManager.isUseVelocityTransfer(),
                configManager.getFallbackLobbySpawn(),
                configManager.getMessage("arena.returning-lobby")
        );

        // 4. Odpočítavanie
        this.countdownManager = new CountdownManager(logger);
        this.countdownManager.attachRuntime(
                plugin,
                messageFormatter,
                configManager.getCountdownNotifyIntervals(),
                configManager.getMessage("countdown.tick"),
                configManager.getMessage("countdown.started"),
                configManager.getMessage("countdown.cancelled-low-players")
        );

        // 5. Herné moduly (SkyRush a Chaos sú prísne oddelené)
        this.gameManager = new GameManager(logger);
        SkyRushModule skyRushModule = new SkyRushModule(
                plugin,
                configManager,
                playerManager,
                networkManager,
                messageFormatter,
                logger
        );
        ChaosModule chaosModule = new ChaosModule(
                plugin,
                configManager,
                arenaManager,
                playerManager,
                networkManager,
                messageFormatter,
                logger
        );
        gameManager.registerModule(skyRushModule);
        gameManager.registerModule(chaosModule);

        // 6. Bezpečný reset máp (sledovanie zmenených blokov + voliteľná obnova z overenej zálohy)
        this.blockTracker = new ArenaBlockTracker(logger);
        Path backupsPath = plugin.getDataFolder().toPath().resolve(configManager.getWorldBackupsDir());
        this.worldBackupManager = new WorldBackupManager(
                logger,
                backupsPath,
                configManager.isWorldBackupEnabled(),
                configManager.isAllowWorldReplacement()
        );
        this.resetManager = new ResetManager(
                plugin,
                configManager,
                gameManager,
                playerManager,
                countdownManager,
                networkManager,
                blockTracker,
                worldBackupManager,
                logger
        );
        skyRushModule.setBlockTracker(blockTracker);
        skyRushModule.setResetManagerSupplier(() -> this.resetManager);
        chaosModule.setResetManagerSupplier(() -> this.resetManager);

        // 7. Matchmaking a spracovanie sieťových správ
        this.matchmakingManager = new MatchmakingManager(
                configManager.getServerId(),
                arenaManager,
                playerManager,
                countdownManager,
                gameManager,
                networkManager,
                logger
        );
        this.matchmakingManager.attachRuntime(configManager, messageFormatter, plugin);

        registerNetworkHandlers(chaosModule);

        gameManager.enableAll();

        // 8. Registrácia listenerov a príkazov
        registerListeners();
        registerCommands();

        // 9. Spustenie sieťového heartbeat schedulera a detekcie nedostupných serverov
        startHeartbeatScheduler();

        logger.info("ArvyxCore v" + getPluginVersion() + " úspešne inicializovaný (server="
                + configManager.getServerId()
                + ", transport=" + networkManager.getTransportMode()
                + ", lokálnych arén=" + arenaManager.getArenaCount() + ").");
    }

    public void reloadConfiguration() {
        configManager.loadAll();
        messageFormatter.setPrefix(configManager.getPrefix());
        playerManager.updateLobbySettings(
                configManager.getLobbyServer(),
                configManager.isUseVelocityTransfer(),
                configManager.getFallbackLobbySpawn(),
                configManager.getMessage("arena.returning-lobby")
        );
        countdownManager.updateSettings(
                configManager.getCountdownNotifyIntervals(),
                configManager.getMessage("countdown.tick"),
                configManager.getMessage("countdown.started"),
                configManager.getMessage("countdown.cancelled-low-players")
        );
        if (worldBackupManager != null) {
            worldBackupManager.updateSettings(
                    configManager.isWorldBackupEnabled(),
                    configManager.isAllowWorldReplacement()
            );
        }
        if (arenaBlockListener != null) {
            arenaBlockListener.setTrackModifiedBlocks(configManager.isRestoreModifiedBlocksOnReset());
        }
        gameManager.reloadAll();
        logger.info("Konfigurácia všetkých modulov bola bezpečne znova načítaná.");
    }

    public void shutdown() {
        logger.info("Vypínanie modulov ArvyxCore (bezpečné ošetrenie aktívnych zápasov)...");
        if (heartbeatTask != null) {
            heartbeatTask.cancel();
            heartbeatTask = null;
        }
        if (countdownManager != null) {
            countdownManager.cancelAll();
        }
        if (resetManager != null) {
            resetManager.cancelAllPendingResets();
        }

        // Ošetrenie prebiehajúcich zápasov pri reštarte/vypnutí backend servera
        if (arenaManager != null && gameManager != null && playerManager != null) {
            for (Arena arena : arenaManager.getAllArenas()) {
                ArenaState state = arena.getState();
                if (state == ArenaState.STARTING || state == ArenaState.INGAME) {
                    gameManager.stopGame(arena);
                    for (UUID uuid : arena.getPlayers()) {
                        Player player = Bukkit.getPlayer(uuid);
                        if (player != null && player.isOnline()) {
                            playerManager.restoreCleanPlayerState(player);
                            playerManager.returnToLobby(player);
                        }
                    }
                    if (blockTracker != null && configManager.isRestoreModifiedBlocksOnReset()) {
                        blockTracker.restoreArenaBlocks(arena);
                    }
                    arena.clearSessionData();
                    if (arena.transitionTo(ArenaState.ENDING)) {
                        arena.transitionTo(ArenaState.DISABLED);
                    } else {
                        arena.transitionTo(ArenaState.DISABLED);
                    }
                    if (networkManager != null) {
                        networkManager.publishArenaStatus(
                                ArenaStatusSnapshot.fromArena(configManager.getServerId(), arena)
                        );
                    }
                }
            }
        }

        if (gameManager != null) {
            gameManager.disableAll();
        }
        if (blockTracker != null) {
            blockTracker.clearAll();
        }
        if (worldBackupManager != null) {
            worldBackupManager.shutdown();
        }
        if (arenaManager != null) {
            arenaManager.clearArenas();
        }
        if (playerManager != null) {
            playerManager.shutdown();
        }
        if (networkManager != null) {
            networkManager.shutdown();
        }
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, PlayerManager.BUNGEECORD_CHANNEL);
        logger.info("ArvyxCore bol čisto vypnutý.");
    }

    private void loadArenasIntoManager() {
        for (Arena arena : configManager.loadConfiguredArenas()) {
            try {
                arenaManager.registerArena(arena);
                networkManager.publishArenaStatus(ArenaStatusSnapshot.fromArena(configManager.getServerId(), arena));
            } catch (Exception ex) {
                logger.error("Nepodarilo sa zaregistrovať arénu '" + arena.getId() + "'", ex);
            }
        }
    }

    private void registerNetworkHandlers(ChaosModule chaosModule) {
        networkManager.registerHandler(NetworkMessageType.PLAYER_TRANSFER_REQUEST, msg -> {
            String target = msg.getTargetServerId();
            if (target == null || target.isBlank()
                    || !target.equalsIgnoreCase(configManager.getServerId())) {
                return;
            }
            if (plugin.isEnabled()) {
                Bukkit.getScheduler().runTask(plugin, () -> matchmakingManager.handleIncomingReservationRequest(msg));
            }
        });

        networkManager.registerHandler(NetworkMessageType.PLAYER_TRANSFER_ACK, msg -> {
            String target = msg.getTargetServerId();
            if (target == null || !target.equalsIgnoreCase(configManager.getServerId())) {
                return;
            }
            if (plugin.isEnabled()) {
                Bukkit.getScheduler().runTask(plugin, () -> matchmakingManager.handleTransferAcknowledgement(msg));
            }
        });

        networkManager.registerHandler(NetworkMessageType.CHAOS_STREAM_TRIGGER, msg -> {
            String target = msg.getTargetServerId();
            if (target != null && !target.isBlank()
                    && !target.equalsIgnoreCase(configManager.getServerId())) {
                return;
            }
            String platform = msg.getPayloadValue("platform");
            String requester = msg.getPayloadValue("requester");
            String arenaId = msg.getPayloadValue("arenaId");
            String eventId = msg.getPayloadValue("eventId");
            if (platform == null || arenaId == null || eventId == null) {
                return;
            }
            Runnable dispatch = () -> chaosModule.getStreamEventBridge().handleTrigger(
                    new StreamTriggerRequest(
                            platform,
                            requester != null ? requester : "stream",
                            arenaId,
                            eventId,
                            msg.getTimestamp()
                    )
            );
            if (plugin.isEnabled()) {
                Bukkit.getScheduler().runTask(plugin, dispatch);
            }
        });

        if (networkManager instanceof RedisNetworkManager redisNetworkManager) {
            redisNetworkManager.setOnReconnectCallback(() -> {
                networkManager.updateLocalHeartbeat(networkManager.getLocalServerInfo().onlinePlayers());
                for (Arena arena : arenaManager.getAllArenas()) {
                    networkManager.publishArenaStatus(
                            ArenaStatusSnapshot.fromArena(configManager.getServerId(), arena)
                    );
                }
            });
        }
    }

    private void registerListeners() {
        plugin.getServer().getPluginManager().registerEvents(
                new PlayerLifecycleListener(
                        configManager.getServerId(),
                        arenaManager,
                        playerManager,
                        matchmakingManager,
                        gameManager,
                        countdownManager,
                        networkManager
                ),
                plugin
        );
        this.arenaBlockListener = new ArenaBlockListener(
                arenaManager,
                blockTracker,
                configManager.isRestoreModifiedBlocksOnReset()
        );
        plugin.getServer().getPluginManager().registerEvents(arenaBlockListener, plugin);
    }

    private void registerCommands() {
        PluginCommand arvyxCmd = plugin.getCommand("arvyx");
        if (arvyxCmd == null) {
            logger.error("Príkaz 'arvyx' nie je definovaný v plugin.yml!");
            return;
        }
        ArvyxCommand executor = new ArvyxCommand(
                this,
                configManager,
                arenaManager,
                playerManager,
                matchmakingManager,
                gameManager,
                countdownManager,
                resetManager,
                networkManager,
                messageFormatter
        );
        arvyxCmd.setExecutor(executor);
        arvyxCmd.setTabCompleter(executor);
    }

    private void startHeartbeatScheduler() {
        long periodTicks = configManager.getHeartbeatIntervalSeconds() * 20L;
        this.heartbeatTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            networkManager.updateLocalHeartbeat(Bukkit.getOnlinePlayers().size());
            networkManager.detectUnavailableServers(Instant.now());
            matchmakingManager.cleanupExpiredReservations(Instant.now());
            for (Arena arena : arenaManager.getAllArenas()) {
                networkManager.publishArenaStatus(ArenaStatusSnapshot.fromArena(configManager.getServerId(), arena));
            }
        }, periodTicks, periodTicks);
    }

    public String getPluginVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    public Logger getLogger() {
        return logger;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public MessageFormatter getMessageFormatter() {
        return messageFormatter;
    }

    public ArenaManager getArenaManager() {
        return arenaManager;
    }

    public PlayerManager getPlayerManager() {
        return playerManager;
    }

    public MatchmakingManager getMatchmakingManager() {
        return matchmakingManager;
    }

    public NetworkManager getNetworkManager() {
        return networkManager;
    }

    public CountdownManager getCountdownManager() {
        return countdownManager;
    }

    public GameManager getGameManager() {
        return gameManager;
    }

    public ResetManager getResetManager() {
        return resetManager;
    }

    public ArenaBlockTracker getBlockTracker() {
        return blockTracker;
    }

    public WorldBackupManager getWorldBackupManager() {
        return worldBackupManager;
    }
}
