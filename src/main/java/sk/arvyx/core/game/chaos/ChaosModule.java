package sk.arvyx.core.game.chaos;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import sk.arvyx.core.arena.Arena;
import sk.arvyx.core.arena.ArenaLocation;
import sk.arvyx.core.arena.ArenaManager;
import sk.arvyx.core.arena.ArenaState;
import sk.arvyx.core.arena.ArenaType;
import sk.arvyx.core.config.ConfigManager;
import sk.arvyx.core.game.GameModule;
import sk.arvyx.core.game.chaos.events.ChaosFogEvent;
import sk.arvyx.core.game.chaos.events.KnockbackStickEvent;
import sk.arvyx.core.game.chaos.events.LowGravityEvent;
import sk.arvyx.core.game.chaos.events.SpeedBoostEvent;
import sk.arvyx.core.game.chaos.events.TntRainEvent;
import sk.arvyx.core.network.ArenaStatusSnapshot;
import sk.arvyx.core.network.NetworkManager;
import sk.arvyx.core.player.PlayerData;
import sk.arvyx.core.player.PlayerManager;
import sk.arvyx.core.reset.ResetManager;
import sk.arvyx.core.util.Logger;
import sk.arvyx.core.util.MessageFormatter;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Herný modul Chaos určený pre streamera a divákov.
 * Úplne izolovaný od režimu SkyRush.
 */
public final class ChaosModule implements GameModule {

    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private final ArenaManager arenaManager;
    private final PlayerManager playerManager;
    private final NetworkManager networkManager;
    private final MessageFormatter messageFormatter;
    private final Logger logger;
    private final ChaosEventManager eventManager;
    private final StreamEventBridge streamEventBridge;

    private final LowGravityEvent lowGravityEvent = new LowGravityEvent();
    private final KnockbackStickEvent knockbackStickEvent = new KnockbackStickEvent();
    private final TntRainEvent tntRainEvent = new TntRainEvent();
    private final SpeedBoostEvent speedBoostEvent = new SpeedBoostEvent();
    private final ChaosFogEvent chaosFogEvent = new ChaosFogEvent();

    private final Map<String, ChaosMatchSession> matchSessions = new ConcurrentHashMap<>();
    private Supplier<ResetManager> resetManagerSupplier;

    private volatile boolean autoRandomEvents = true;
    private volatile int randomEventIntervalSeconds = 25;

    public ChaosModule(
            JavaPlugin plugin,
            ConfigManager configManager,
            ArenaManager arenaManager,
            PlayerManager playerManager,
            NetworkManager networkManager,
            MessageFormatter messageFormatter,
            Logger logger
    ) {
        this.plugin = Objects.requireNonNull(plugin, "Plugin nesmie byť null");
        this.configManager = Objects.requireNonNull(configManager, "ConfigManager nesmie byť null");
        this.arenaManager = Objects.requireNonNull(arenaManager, "ArenaManager nesmie byť null");
        this.playerManager = Objects.requireNonNull(playerManager, "PlayerManager nesmie byť null");
        this.networkManager = Objects.requireNonNull(networkManager, "NetworkManager nesmie byť null");
        this.messageFormatter = Objects.requireNonNull(messageFormatter, "MessageFormatter nesmie byť null");
        this.logger = Objects.requireNonNull(logger, "Logger nesmie byť null");

        this.eventManager = new ChaosEventManager(logger);
        this.eventManager.setContext(new ChaosEventContext(plugin, logger, messageFormatter));
        registerDefaultEvents(this.eventManager);

        this.streamEventBridge = new StreamEventBridge(arenaManager, eventManager, logger);
    }

    public static void registerDefaultEvents(ChaosEventManager manager) {
        manager.registerEvent(new LowGravityEvent());
        manager.registerEvent(new KnockbackStickEvent());
        manager.registerEvent(new TntRainEvent());
        manager.registerEvent(new SpeedBoostEvent());
        manager.registerEvent(new ChaosFogEvent());
    }

    public void setResetManagerSupplier(Supplier<ResetManager> resetManagerSupplier) {
        this.resetManagerSupplier = resetManagerSupplier;
    }

    public ChaosEventManager getEventManager() {
        return eventManager;
    }

    public StreamEventBridge getStreamEventBridge() {
        return streamEventBridge;
    }

    @Override
    public ArenaType getType() {
        return ArenaType.CHAOS;
    }

    @Override
    public String getName() {
        return "Chaos";
    }

    @Override
    public void onEnable() {
        // Re-register instance-held configurable events so reload updates apply directly
        reloadConfig();
        logger.info("Herný modul Chaos bol aktivovaný (" + eventManager.getRegisteredEvents().size() + " udalostí).");
    }

    @Override
    public void onDisable() {
        for (ChaosMatchSession session : matchSessions.values()) {
            session.cancelTask();
            eventManager.deactivateAll(session.arena);
        }
        matchSessions.clear();
        logger.info("Herný modul Chaos bol bezpečne vypnutý.");
    }

    @Override
    public void reloadConfig() {
        FileConfiguration cfg = configManager.getChaosConfig();
        this.autoRandomEvents = cfg.getBoolean("chaos.auto-random-events", true);
        this.randomEventIntervalSeconds = Math.max(5, cfg.getInt("chaos.random-event-interval-seconds", 25));
        int maxConcurrent = Math.max(1, cfg.getInt("chaos.max-concurrent-events", 2));
        eventManager.setMaxConcurrentEvents(maxConcurrent);
        streamEventBridge.setStreamingEnabled(cfg.getBoolean("chaos.streaming.enabled", false));

        applyEventSettings(cfg, lowGravityEvent);
        applyEventSettings(cfg, knockbackStickEvent);
        applyEventSettings(cfg, tntRainEvent);
        applyEventSettings(cfg, speedBoostEvent);
        applyEventSettings(cfg, chaosFogEvent);

        // Synchronizácia s registrovanými udalosťami v eventManageri
        eventManager.getEvent(LowGravityEvent.ID).ifPresent(e -> applyEventSettings(cfg, e));
        eventManager.getEvent(KnockbackStickEvent.ID).ifPresent(e -> applyEventSettings(cfg, e));
        eventManager.getEvent(TntRainEvent.ID).ifPresent(e -> applyEventSettings(cfg, e));
        eventManager.getEvent(SpeedBoostEvent.ID).ifPresent(e -> applyEventSettings(cfg, e));
        eventManager.getEvent(ChaosFogEvent.ID).ifPresent(e -> applyEventSettings(cfg, e));

        ConfigurationSection lowGravSec = cfg.getConfigurationSection("chaos.events.LOW_GRAVITY");
        if (lowGravSec != null) {
            eventManager.getEvent(LowGravityEvent.ID).ifPresent(e -> {
                if (e instanceof LowGravityEvent lg) {
                    lg.setGravityMultiplier(lowGravSec.getDouble("gravity-multiplier", 0.35));
                }
            });
        }
        ConfigurationSection kbSec = cfg.getConfigurationSection("chaos.events.KNOCKBACK_STICK");
        if (kbSec != null) {
            eventManager.getEvent(KnockbackStickEvent.ID).ifPresent(e -> {
                if (e instanceof KnockbackStickEvent kb) {
                    kb.setKnockbackLevel(kbSec.getInt("knockback-level", 4));
                }
            });
        }
        ConfigurationSection tntSec = cfg.getConfigurationSection("chaos.events.TNT_RAIN");
        if (tntSec != null) {
            eventManager.getEvent(TntRainEvent.ID).ifPresent(e -> {
                if (e instanceof TntRainEvent tr) {
                    tr.setTntPerWave(tntSec.getInt("tnt-per-wave", 3));
                    tr.setSpawnHeight(tntSec.getDouble("spawn-height", 14.0));
                    tr.setSpreadRadius(tntSec.getDouble("spread-radius", 16.0));
                }
            });
        }
        ConfigurationSection speedSec = cfg.getConfigurationSection("chaos.events.SPEED_BOOST");
        if (speedSec != null) {
            eventManager.getEvent(SpeedBoostEvent.ID).ifPresent(e -> {
                if (e instanceof SpeedBoostEvent sb) {
                    sb.setAmplifier(speedSec.getInt("amplifier", 2));
                }
            });
        }
        ConfigurationSection fogSec = cfg.getConfigurationSection("chaos.events.CHAOS_FOG");
        if (fogSec != null) {
            eventManager.getEvent(ChaosFogEvent.ID).ifPresent(e -> {
                if (e instanceof ChaosFogEvent cf) {
                    cf.setAmplifier(fogSec.getInt("amplifier", 0));
                }
            });
        }
    }

    private void applyEventSettings(FileConfiguration cfg, ChaosEvent event) {
        ConfigurationSection sec = cfg.getConfigurationSection("chaos.events." + event.getId());
        if (sec == null) {
            return;
        }
        event.setEnabled(sec.getBoolean("enabled", true));
        event.setName(sec.getString("name", event.getName()));
        event.setDescription(sec.getString("description", event.getDescription()));
        event.setDurationSeconds(sec.getInt("duration-seconds", event.getDurationSeconds()));
        event.setCooldownSeconds(sec.getInt("cooldown-seconds", event.getCooldownSeconds()));
    }

    @Override
    public void onArenaStart(Arena arena) {
        String key = normalizeKey(arena.getId());
        ChaosMatchSession previous = matchSessions.remove(key);
        if (previous != null) {
            previous.cancelTask();
        }
        eventManager.deactivateAll(arena);

        World world = Bukkit.getWorld(arena.getWorldName());
        if (world == null) {
            logger.warn("Svet '" + arena.getWorldName() + "' pre Chaos arénu '" + arena.getId() + "' nie je načítaný.");
        }

        List<ArenaLocation> spawns = arena.getSpawnPoints();
        int idx = 0;
        for (UUID uuid : arena.getPlayers()) {
            playerManager.getPlayerData(uuid).ifPresent(data -> data.setAlive(true));
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                playerManager.restoreCleanPlayerState(player);
                player.setGameMode(GameMode.SURVIVAL);
                player.getInventory().addItem(new ItemStack(Material.STONE_SWORD));
                player.getInventory().addItem(new ItemStack(Material.COOKED_BEEF, 8));
                if (world != null) {
                    Location target = spawns.isEmpty()
                            ? arena.getCenterLocation().toBukkitLocation(world)
                            : spawns.get(idx % spawns.size()).toBukkitLocation(world);
                    player.teleport(target);
                }
                idx++;
            }
        }

        ChaosMatchSession session = new ChaosMatchSession(arena, arena.getMatchDurationSeconds(), randomEventIntervalSeconds);
        matchSessions.put(key, session);

        if (plugin.isEnabled()) {
            BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> tickMatch(session), 20L, 20L);
            session.setTask(task);
        }

        networkManager.publishArenaStatus(ArenaStatusSnapshot.fromArena(configManager.getServerId(), arena));
        logger.info("Chaos zápas spustený v aréne '" + arena.getId() + "'.");
    }

    private void tickMatch(ChaosMatchSession session) {
        Arena arena = session.arena;
        if (arena.getState() != ArenaState.INGAME) {
            onArenaStop(arena);
            return;
        }

        // 1. Tick aktívnych Chaos udalostí
        List<ChaosEvent> expired = eventManager.tickArenaEvents(arena);
        for (ChaosEvent ended : expired) {
            broadcastArena(arena, configManager.getMessage("chaos.event-ended"), Map.of(
                    "event_name", ended.getName(),
                    "event_id", ended.getId()
            ));
        }

        // 2. Automatická náhodná udalosť
        if (autoRandomEvents) {
            session.secondsUntilNextRandomEvent--;
            if (session.secondsUntilNextRandomEvent <= 0) {
                session.secondsUntilNextRandomEvent = randomEventIntervalSeconds;
                Optional<ChaosEvent> triggered = eventManager.triggerRandomEvent(arena);
                triggered.ifPresent(event -> announceEventActivated(arena, event));
            }
        }

        // 3. Kontrola konca zápasu
        session.remainingMatchSeconds--;
        if (arena.getAliveCount() <= 1 || session.remainingMatchSeconds <= 0) {
            concludeMatch(arena);
        }
    }

    public ChaosEventManager.TriggerResult triggerManualEvent(Arena arena, String eventId) {
        ChaosEventManager.TriggerResult result = eventManager.triggerEvent(arena, eventId, true);
        if (result == ChaosEventManager.TriggerResult.ACTIVATED) {
            eventManager.getEvent(eventId).ifPresent(event -> announceEventActivated(arena, event));
        }
        return result;
    }

    private void announceEventActivated(Arena arena, ChaosEvent event) {
        broadcastArena(arena, configManager.getMessage("chaos.event-activated"), Map.of(
                "event_name", event.getName(),
                "description", event.getDescription(),
                "duration", String.valueOf(event.getDurationSeconds())
        ));
        for (UUID uuid : arena.getPlayers()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 0.9f);
            }
        }
    }

    @Override
    public void onArenaStop(Arena arena) {
        ChaosMatchSession session = matchSessions.remove(normalizeKey(arena.getId()));
        if (session != null) {
            session.cancelTask();
        }
        eventManager.deactivateAll(arena);
    }

    @Override
    public void onArenaReset(Arena arena) {
        onArenaStop(arena);
    }

    @Override
    public void onPlayerEliminate(Arena arena, UUID victimId, UUID killerId) {
        if (arena.getState() != ArenaState.INGAME) {
            return;
        }
        if (!arena.markEliminated(victimId)) {
            return;
        }
        playerManager.getPlayerData(victimId).ifPresent(data -> {
            data.setAlive(false);
            data.incrementDeaths();
        });
        if (killerId != null && !killerId.equals(victimId)) {
            playerManager.getPlayerData(killerId).ifPresent(PlayerData::incrementKills);
        }
        Player victim = Bukkit.getPlayer(victimId);
        if (victim != null && victim.isOnline()) {
            victim.setGameMode(GameMode.SPECTATOR);
        }
        if (arena.getAliveCount() <= 1) {
            concludeMatch(arena);
        }
    }

    @Override
    public void onPlayerQuit(Arena arena, UUID playerId) {
        if (arena.getState() == ArenaState.INGAME) {
            arena.markEliminated(playerId);
            if (arena.getAliveCount() <= 1) {
                concludeMatch(arena);
            }
        }
    }

    @Override
    public boolean isGracePeriodActive(Arena arena) {
        return false;
    }

    private void concludeMatch(Arena arena) {
        ChaosMatchSession session = matchSessions.remove(normalizeKey(arena.getId()));
        if (session != null) {
            session.cancelTask();
        }
        eventManager.deactivateAll(arena);

        if (arena.getState() == ArenaState.INGAME) {
            arena.transitionTo(ArenaState.ENDING);
        }

        Optional<UUID> winnerId = arena.getAlivePlayers().stream().findFirst();
        String winnerName = winnerId
                .flatMap(id -> playerManager.getPlayerData(id).map(PlayerData::getLastKnownName))
                .orElse("Nikto");

        winnerId.flatMap(playerManager::getPlayerData).ifPresent(PlayerData::incrementWins);
        broadcastArena(arena, configManager.getMessage("chaos.winner"), Map.of("winner", winnerName));

        networkManager.publishArenaStatus(ArenaStatusSnapshot.fromArena(configManager.getServerId(), arena));
        if (resetManagerSupplier != null && resetManagerSupplier.get() != null) {
            resetManagerSupplier.get().scheduleResetAfterEnding(arena);
        }
    }

    private void broadcastArena(Arena arena, String template, Map<String, String> placeholders) {
        for (UUID uuid : arena.getPlayers()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                messageFormatter.send(player, template, placeholders);
            }
        }
    }

    private static String normalizeKey(String id) {
        return id.trim().toLowerCase(Locale.ROOT);
    }

    private static final class ChaosMatchSession {
        private final Arena arena;
        private int remainingMatchSeconds;
        private int secondsUntilNextRandomEvent;
        private BukkitTask task;

        private ChaosMatchSession(Arena arena, int matchDurationSeconds, int randomInterval) {
            this.arena = arena;
            this.remainingMatchSeconds = matchDurationSeconds;
            this.secondsUntilNextRandomEvent = randomInterval;
        }

        private void setTask(BukkitTask task) {
            this.task = task;
        }

        private void cancelTask() {
            if (task != null) {
                task.cancel();
                task = null;
            }
        }
    }
}
