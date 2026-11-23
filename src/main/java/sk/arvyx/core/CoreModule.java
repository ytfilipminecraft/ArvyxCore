package sk.arvyx.core;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import sk.arvyx.core.arena.Arena;
import sk.arvyx.core.arena.ArenaManager;
import sk.arvyx.core.command.ArvyxCommand;
import sk.arvyx.core.config.ConfigManager;
import sk.arvyx.core.countdown.CountdownManager;
import sk.arvyx.core.game.GameManager;
import sk.arvyx.core.game.chaos.ChaosModule;
import sk.arvyx.core.game.skyrush.SkyRushModule;
import sk.arvyx.core.listener.PlayerLifecycleListener;
import sk.arvyx.core.network.ArenaStatusSnapshot;
import sk.arvyx.core.network.LocalNetworkManager;
import sk.arvyx.core.network.NetworkManager;
import sk.arvyx.core.player.PlayerManager;
import sk.arvyx.core.reset.ResetManager;
import sk.arvyx.core.util.Logger;
import sk.arvyx.core.util.MessageFormatter;

import java.util.Objects;

/**
 * Hlavný koordinačný modul jadra ArvyxCore.
 * Zodpovedá za inicializáciu modulov, načítanie konfigurácie, registráciu listenerov, príkazov a čisté vypnutie.
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
    private ResetManager resetManager;
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

        // 2. Sieťová vrstva (funguje aj bez Redis)
        this.networkManager = new LocalNetworkManager(
                logger,
                configManager.getServerId(),
                configManager.getServerRole(),
                configManager.getServerDisplayName(),
                configManager.isRedisEnabled()
        );
        this.networkManager.initialize();

        // Registrácia BungeeCord/Velocity outgoing kanála pre bezpečný návrat hráčov do lobby
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, PlayerManager.BUNGEECORD_CHANNEL);

        // 3. Správa arén a hráčov
        this.arenaManager = new ArenaManager(logger);
        loadArenasIntoManager();

        this.playerManager = new PlayerManager(arenaManager, logger);
        this.playerManager.attachRuntime(
                plugin,
                messageFormatter,
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

        // 6. Bezpečný reset arén
        this.resetManager = new ResetManager(
                plugin,
                configManager,
                gameManager,
                playerManager,
                countdownManager,
                networkManager,
                logger
        );
        skyRushModule.setResetManagerSupplier(() -> this.resetManager);
        chaosModule.setResetManagerSupplier(() -> this.resetManager);

        gameManager.enableAll();

        // 7. Registrácia listenerov a príkazov
        registerListeners();
        registerCommands();

        // 8. Spustenie sieťového heartbeat schedulera
        startHeartbeatScheduler();

        logger.info("ArvyxCore úspešne inicializovaný (server=" + configManager.getServerId()
                + ", arén=" + arenaManager.getArenaCount() + ").");
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
        gameManager.reloadAll();
        logger.info("Konfigurácia všetkých modulov bola bezpečne znova načítaná.");
    }

    public void shutdown() {
        logger.info("Vypínanie modulov ArvyxCore...");
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
        if (gameManager != null) {
            gameManager.disableAll();
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

    private void registerListeners() {
        plugin.getServer().getPluginManager().registerEvents(
                new PlayerLifecycleListener(
                        configManager.getServerId(),
                        arenaManager,
                        playerManager,
                        gameManager,
                        countdownManager,
                        networkManager
                ),
                plugin
        );
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
}
