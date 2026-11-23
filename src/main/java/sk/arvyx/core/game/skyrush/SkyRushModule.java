package sk.arvyx.core.game.skyrush;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import sk.arvyx.core.arena.Arena;
import sk.arvyx.core.arena.ArenaLocation;
import sk.arvyx.core.arena.ArenaState;
import sk.arvyx.core.arena.ArenaType;
import sk.arvyx.core.config.ConfigManager;
import sk.arvyx.core.game.GameModule;
import sk.arvyx.core.network.ArenaStatusSnapshot;
import sk.arvyx.core.network.NetworkManager;
import sk.arvyx.core.player.PlayerData;
import sk.arvyx.core.player.PlayerManager;
import sk.arvyx.core.reset.ResetManager;
import sk.arvyx.core.util.Logger;
import sk.arvyx.core.util.MessageFormatter;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Herný modul SkyRush — rýchly ostrovný PvP režim (3-5 minút) s loot truhlicami a Supply Dropmi.
 * Úplne izolovaný od ostatných herných režimov.
 */
public final class SkyRushModule implements GameModule {

    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private final PlayerManager playerManager;
    private final NetworkManager networkManager;
    private final MessageFormatter messageFormatter;
    private final Logger logger;
    private final SkyRushLootManager lootManager;

    private final Map<String, SkyRushMatchSession> sessions = new ConcurrentHashMap<>();
    private Supplier<ResetManager> resetManagerSupplier;
    private volatile SkyRushConfig skyRushConfig;

    public SkyRushModule(
            JavaPlugin plugin,
            ConfigManager configManager,
            PlayerManager playerManager,
            NetworkManager networkManager,
            MessageFormatter messageFormatter,
            Logger logger
    ) {
        this.plugin = Objects.requireNonNull(plugin, "Plugin nesmie byť null");
        this.configManager = Objects.requireNonNull(configManager, "ConfigManager nesmie byť null");
        this.playerManager = Objects.requireNonNull(playerManager, "PlayerManager nesmie byť null");
        this.networkManager = Objects.requireNonNull(networkManager, "NetworkManager nesmie byť null");
        this.messageFormatter = Objects.requireNonNull(messageFormatter, "MessageFormatter nesmie byť null");
        this.logger = Objects.requireNonNull(logger, "Logger nesmie byť null");
        this.lootManager = new SkyRushLootManager(logger);
    }

    public void setResetManagerSupplier(Supplier<ResetManager> resetManagerSupplier) {
        this.resetManagerSupplier = resetManagerSupplier;
    }

    @Override
    public ArenaType getType() {
        return ArenaType.SKYRUSH;
    }

    @Override
    public String getName() {
        return "SkyRush";
    }

    @Override
    public void onEnable() {
        reloadConfig();
        logger.info("Herný modul SkyRush bol aktivovaný (cieľ zápasu=" + skyRushConfig.getMatchDurationSeconds() + "s).");
    }

    @Override
    public void onDisable() {
        for (SkyRushMatchSession session : sessions.values()) {
            session.cancelTask();
        }
        sessions.clear();
        logger.info("Herný modul SkyRush bol bezpečne vypnutý.");
    }

    @Override
    public void reloadConfig() {
        this.skyRushConfig = SkyRushConfig.fromConfig(configManager);
    }

    @Override
    public void onArenaStart(Arena arena) {
        SkyRushConfig cfg = this.skyRushConfig != null ? this.skyRushConfig : SkyRushConfig.fromConfig(configManager);
        String key = normalizeKey(arena.getId());
        SkyRushMatchSession previous = sessions.remove(key);
        if (previous != null) {
            previous.cancelTask();
        }

        World world = Bukkit.getWorld(arena.getWorldName());
        if (world == null) {
            logger.warn("Svet '" + arena.getWorldName() + "' pre SkyRush arénu '" + arena.getId() + "' nie je načítaný.");
        }

        // Rozmiestnenie hráčov na ostrovy a štartovací kit
        List<ArenaLocation> islands = arena.getSpawnPoints();
        int idx = 0;
        for (UUID uuid : arena.getPlayers()) {
            playerManager.getPlayerData(uuid).ifPresent(data -> data.setAlive(true));
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                playerManager.restoreCleanPlayerState(player);
                player.setGameMode(GameMode.SURVIVAL);
                giveStarterKit(player, cfg);
                if (world != null) {
                    Location spawnLoc = islands.isEmpty()
                            ? arena.getCenterLocation().toBukkitLocation(world)
                            : islands.get(idx % islands.size()).toBukkitLocation(world);
                    player.teleport(spawnLoc);
                }
                idx++;
            }
        }

        // Naplnenie ostrovných a centrálnych truhlíc
        int chestCount = lootManager.populateArenaChests(arena, cfg);
        logger.debug("Naplnených " + chestCount + " truhlíc v SkyRush aréne '" + arena.getId() + "'.");

        int duration = Math.min(arena.getMatchDurationSeconds(), cfg.getMatchDurationSeconds());
        SkyRushMatchSession session = new SkyRushMatchSession(
                arena,
                duration,
                cfg.getGracePeriodSeconds(),
                cfg.getSupplyDropIntervalSeconds(),
                cfg.getChestRefillSeconds()
        );
        sessions.put(key, session);

        if (cfg.getGracePeriodSeconds() > 0) {
            broadcastArena(arena, configManager.getMessage("skyrush.grace-start"), Map.of(
                    "seconds", String.valueOf(cfg.getGracePeriodSeconds())
            ));
        }

        if (plugin.isEnabled()) {
            BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> tickMatch(session, cfg), 20L, 20L);
            session.setTask(task);
        }

        networkManager.publishArenaStatus(ArenaStatusSnapshot.fromArena(configManager.getServerId(), arena));
        logger.info("SkyRush zápas spustený v aréne '" + arena.getId() + "'.");
    }

    private void giveStarterKit(Player player, SkyRushConfig cfg) {
        for (SkyRushConfig.KitItem item : cfg.getStarterKit()) {
            player.getInventory().addItem(new ItemStack(item.material(), item.amount()));
        }
    }

    private void tickMatch(SkyRushMatchSession session, SkyRushConfig cfg) {
        Arena arena = session.arena;
        if (arena.getState() != ArenaState.INGAME) {
            onArenaStop(arena);
            return;
        }

        // 1. Ochranná lehota na začiatku zápasu
        if (session.graceRemainingSeconds > 0) {
            session.graceRemainingSeconds--;
            if (session.graceRemainingSeconds == 0) {
                broadcastArena(arena, configManager.getMessage("skyrush.grace-end"), Map.of());
                playArenaSound(arena, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f);
            }
        }

        // 2. Kontrola pádu do prázdna (Void check)
        for (UUID uuid : arena.getAlivePlayers()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()
                    && player.getWorld().getName().equalsIgnoreCase(arena.getWorldName())
                    && player.getLocation().getY() < cfg.getVoidYLevel()) {
                onPlayerEliminate(arena, uuid, null);
            }
        }

        if (arena.getState() != ArenaState.INGAME) {
            return;
        }

        // 3. Doplnenie truhlíc
        session.secondsUntilChestRefill--;
        if (session.secondsUntilChestRefill <= 0) {
            session.secondsUntilChestRefill = cfg.getChestRefillSeconds();
            lootManager.populateArenaChests(arena, cfg);
            broadcastArena(arena, configManager.getMessage("skyrush.chest-refill"), Map.of());
            playArenaSound(arena, Sound.BLOCK_CHEST_OPEN, 1.2f);
        }

        // 4. Supply drop na strede mapy
        session.secondsUntilSupplyDrop--;
        if (session.secondsUntilSupplyDrop <= 0) {
            session.secondsUntilSupplyDrop = cfg.getSupplyDropIntervalSeconds();
            if (lootManager.spawnSupplyDrop(arena, cfg)) {
                broadcastArena(arena, configManager.getMessage("skyrush.supply-drop"), Map.of());
                playArenaSound(arena, Sound.ENTITY_ENDER_DRAGON_GROWL, 1.2f);
            }
        }

        // 5. Časový limit a vyhodnotenie víťaza
        session.remainingMatchSeconds--;
        if (arena.getAliveCount() <= 1) {
            concludeMatch(arena, false);
        } else if (session.remainingMatchSeconds <= 0) {
            concludeMatch(arena, true);
        }
    }

    @Override
    public void onArenaStop(Arena arena) {
        SkyRushMatchSession session = sessions.remove(normalizeKey(arena.getId()));
        if (session != null) {
            session.cancelTask();
        }
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
        String victimName = victim != null
                ? victim.getName()
                : playerManager.getPlayerData(victimId).map(PlayerData::getLastKnownName).orElse("Neznámy");

        if (victim != null && victim.isOnline()) {
            victim.setGameMode(GameMode.SPECTATOR);
            arena.getLobbySpawn().toBukkitLocation().ifPresent(victim::teleport);
        }

        broadcastArena(arena, configManager.getMessage("skyrush.eliminated"), Map.of(
                "player", victimName,
                "remaining", String.valueOf(arena.getAliveCount())
        ));

        if (arena.getAliveCount() <= 1) {
            concludeMatch(arena, false);
        }
    }

    @Override
    public void onPlayerQuit(Arena arena, UUID playerId) {
        if (arena.getState() == ArenaState.INGAME && arena.isPlayerAlive(playerId)) {
            onPlayerEliminate(arena, playerId, null);
        }
    }

    @Override
    public boolean isGracePeriodActive(Arena arena) {
        if (arena == null) {
            return false;
        }
        SkyRushMatchSession session = sessions.get(normalizeKey(arena.getId()));
        return session != null && session.graceRemainingSeconds > 0;
    }

    private void concludeMatch(Arena arena, boolean byTimeLimit) {
        SkyRushMatchSession session = sessions.remove(normalizeKey(arena.getId()));
        if (session != null) {
            session.cancelTask();
        }

        if (arena.getState() == ArenaState.INGAME) {
            arena.transitionTo(ArenaState.ENDING);
        }

        Optional<UUID> winnerOpt = evaluateWinner(arena);
        if (winnerOpt.isPresent()) {
            UUID winnerId = winnerOpt.get();
            Optional<PlayerData> winnerData = playerManager.getPlayerData(winnerId);
            winnerData.ifPresent(PlayerData::incrementWins);
            String winnerName = winnerData.map(PlayerData::getLastKnownName).orElse("Víťaz");
            int kills = winnerData.map(PlayerData::getKills).orElse(0);

            String messageKey = byTimeLimit ? "skyrush.time-limit-winner" : "skyrush.winner";
            broadcastArena(arena, configManager.getMessage(messageKey), Map.of(
                    "winner", winnerName,
                    "kills", String.valueOf(kills)
            ));
            playArenaSound(arena, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f);
        }

        networkManager.publishArenaStatus(ArenaStatusSnapshot.fromArena(configManager.getServerId(), arena));
        if (resetManagerSupplier != null && resetManagerSupplier.get() != null) {
            resetManagerSupplier.get().scheduleResetAfterEnding(arena);
        }
    }

    private Optional<UUID> evaluateWinner(Arena arena) {
        return arena.getAlivePlayers().stream()
                .max(Comparator.comparingInt(uuid ->
                        playerManager.getPlayerData(uuid).map(PlayerData::getKills).orElse(0)));
    }

    private void broadcastArena(Arena arena, String template, Map<String, String> placeholders) {
        for (UUID uuid : arena.getPlayers()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                messageFormatter.send(player, template, placeholders);
            }
        }
    }

    private void playArenaSound(Arena arena, Sound sound, float pitch) {
        for (UUID uuid : arena.getPlayers()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                player.playSound(player.getLocation(), sound, 1.0f, pitch);
            }
        }
    }

    private static String normalizeKey(String id) {
        return id.trim().toLowerCase(Locale.ROOT);
    }

    private static final class SkyRushMatchSession {
        private final Arena arena;
        private int remainingMatchSeconds;
        private int graceRemainingSeconds;
        private int secondsUntilSupplyDrop;
        private int secondsUntilChestRefill;
        private BukkitTask task;

        private SkyRushMatchSession(
                Arena arena,
                int remainingMatchSeconds,
                int graceRemainingSeconds,
                int supplyDropIntervalSeconds,
                int chestRefillSeconds
        ) {
            this.arena = arena;
            this.remainingMatchSeconds = remainingMatchSeconds;
            this.graceRemainingSeconds = graceRemainingSeconds;
            this.secondsUntilSupplyDrop = supplyDropIntervalSeconds;
            this.secondsUntilChestRefill = chestRefillSeconds;
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
