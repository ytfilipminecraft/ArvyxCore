package sk.arvyx.core.reset;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import sk.arvyx.core.arena.Arena;
import sk.arvyx.core.arena.ArenaState;
import sk.arvyx.core.config.ConfigManager;
import sk.arvyx.core.countdown.CountdownManager;
import sk.arvyx.core.game.GameManager;
import sk.arvyx.core.network.ArenaStatusSnapshot;
import sk.arvyx.core.network.NetworkManager;
import sk.arvyx.core.player.PlayerManager;
import sk.arvyx.core.util.Logger;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bezpečný správca resetovania arén po skončení zápasu.
 * Nikdy nemaže priečinky svetov — čistí iba dočasné herné entity, stav hráčov a herné relácie.
 */
public final class ResetManager {

    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private final GameManager gameManager;
    private final PlayerManager playerManager;
    private final CountdownManager countdownManager;
    private final NetworkManager networkManager;
    private final Logger logger;

    private final Map<String, BukkitTask> pendingResetTasks = new ConcurrentHashMap<>();

    public ResetManager(
            JavaPlugin plugin,
            ConfigManager configManager,
            GameManager gameManager,
            PlayerManager playerManager,
            CountdownManager countdownManager,
            NetworkManager networkManager,
            Logger logger
    ) {
        this.plugin = Objects.requireNonNull(plugin, "Plugin nesmie byť null");
        this.configManager = Objects.requireNonNull(configManager, "ConfigManager nesmie byť null");
        this.gameManager = Objects.requireNonNull(gameManager, "GameManager nesmie byť null");
        this.playerManager = Objects.requireNonNull(playerManager, "PlayerManager nesmie byť null");
        this.countdownManager = Objects.requireNonNull(countdownManager, "CountdownManager nesmie byť null");
        this.networkManager = Objects.requireNonNull(networkManager, "NetworkManager nesmie byť null");
        this.logger = Objects.requireNonNull(logger, "Logger nesmie byť null");
    }

    /**
     * Naplánuje bezpečný reset po fáze ENDING. Zabraňuje duplicitnému naplánovaniu.
     */
    public void scheduleResetAfterEnding(Arena arena) {
        Objects.requireNonNull(arena, "Aréna nesmie byť null");
        String key = normalizeKey(arena.getId());
        if (pendingResetTasks.containsKey(key)) {
            return;
        }
        long delayTicks = configManager.getEndingDelaySeconds() * 20L;
        if (!plugin.isEnabled()) {
            resetArenaNow(arena);
            return;
        }
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            pendingResetTasks.remove(key);
            resetArenaNow(arena);
        }, delayTicks);
        pendingResetTasks.put(key, task);
    }

    /**
     * Vykoná okamžitý a bezpečný reset arény na hlavnom vlákne servera.
     */
    public boolean resetArenaNow(Arena arena) {
        if (arena == null) {
            return false;
        }
        String key = normalizeKey(arena.getId());
        BukkitTask pending = pendingResetTasks.remove(key);
        if (pending != null) {
            pending.cancel();
        }

        countdownManager.cancelCountdown(arena.getId(), false);

        if (arena.getState() == ArenaState.INGAME || arena.getState() == ArenaState.STARTING) {
            gameManager.stopGame(arena);
        }

        if (arena.getState() != ArenaState.RESETTING) {
            if (!arena.transitionTo(ArenaState.RESETTING)) {
                if (arena.getState() == ArenaState.DISABLED) {
                    logger.warn("Aréna '" + arena.getId() + "' je DISABLED; reset sa preskakuje.");
                    return false;
                }
                if (arena.getState() == ArenaState.STARTING && arena.transitionTo(ArenaState.ENDING)) {
                    arena.transitionTo(ArenaState.RESETTING);
                } else {
                    logger.warn("Nepodarilo sa prejsť do stavu RESETTING pre arénu '" + arena.getId()
                            + "' zo stavu " + arena.getState());
                    return false;
                }
            }
        }

        networkManager.publishArenaStatus(ArenaStatusSnapshot.fromArena(configManager.getServerId(), arena));

        // 1. Deaktivácia modulových úloh a udalostí
        gameManager.resetGame(arena);

        // 2. Obnovenie a vrátenie hráčov
        boolean restorePlayers = configManager.isRestorePlayerStateOnReset();
        for (UUID uuid : arena.getPlayers()) {
            Player player = Bukkit.getPlayer(uuid);
            playerManager.clearArenaAssignment(uuid);
            if (player != null && player.isOnline()) {
                if (restorePlayers) {
                    playerManager.restoreCleanPlayerState(player);
                }
                playerManager.returnToLobby(player);
            }
        }
        arena.clearSessionData();

        // 3. Vyčistenie dočasných entít v cieľovom svete (nikdy nie hráčov ani priečinkov svetov)
        if (configManager.isClearTemporaryEntitiesOnReset()) {
            clearTemporaryEntities(arena);
        }

        // 4. Obnovenie herných nastavení sveta
        restoreWorldSettings(arena);

        // 5. Pripravenie novej relácie
        boolean transitioned = arena.transitionTo(ArenaState.WAITING);
        networkManager.publishArenaStatus(ArenaStatusSnapshot.fromArena(configManager.getServerId(), arena));

        if (transitioned) {
            logger.info("Aréna '" + arena.getId() + "' bola bezpečne resetovaná a je pripravená (WAITING).");
        }
        return transitioned;
    }

    private void clearTemporaryEntities(Arena arena) {
        World world = Bukkit.getWorld(arena.getWorldName());
        if (world == null) {
            logger.debug("Svet '" + arena.getWorldName() + "' pre arénu '" + arena.getId() + "' nie je načítaný — čistenie entít preskočené.");
            return;
        }
        int removedCount = 0;
        for (Entity entity : world.getEntities()) {
            if (entity instanceof Item
                    || entity instanceof TNTPrimed
                    || entity instanceof AbstractArrow
                    || entity instanceof ExperienceOrb
                    || entity instanceof Projectile
                    || entity instanceof FallingBlock) {
                entity.remove();
                removedCount++;
            }
        }
        logger.debug("Vyčistených " + removedCount + " dočasných entít vo svete '" + world.getName() + "'.");
    }

    private void restoreWorldSettings(Arena arena) {
        World world = Bukkit.getWorld(arena.getWorldName());
        if (world == null) {
            return;
        }
        world.setStorm(false);
        world.setThundering(false);
        world.setTime(6000L);
    }

    public void cancelAllPendingResets() {
        for (BukkitTask task : pendingResetTasks.values()) {
            task.cancel();
        }
        pendingResetTasks.clear();
    }

    private static String normalizeKey(String id) {
        return id.trim().toLowerCase(Locale.ROOT);
    }
}
