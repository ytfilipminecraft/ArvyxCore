package sk.arvyx.core.listener;

import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import sk.arvyx.core.arena.Arena;
import sk.arvyx.core.arena.ArenaManager;
import sk.arvyx.core.arena.ArenaState;
import sk.arvyx.core.countdown.CountdownManager;
import sk.arvyx.core.game.GameManager;
import sk.arvyx.core.network.ArenaStatusSnapshot;
import sk.arvyx.core.network.NetworkManager;
import sk.arvyx.core.player.PlayerManager;

import java.util.Optional;
import java.util.UUID;

/**
 * Základný listener životného cyklu hráčov v arénach a na serveri.
 */
public final class PlayerLifecycleListener implements Listener {

    private final String serverId;
    private final ArenaManager arenaManager;
    private final PlayerManager playerManager;
    private final GameManager gameManager;
    private final CountdownManager countdownManager;
    private final NetworkManager networkManager;

    public PlayerLifecycleListener(
            String serverId,
            ArenaManager arenaManager,
            PlayerManager playerManager,
            GameManager gameManager,
            CountdownManager countdownManager,
            NetworkManager networkManager
    ) {
        this.serverId = serverId;
        this.arenaManager = arenaManager;
        this.playerManager = playerManager;
        this.gameManager = gameManager;
        this.countdownManager = countdownManager;
        this.networkManager = networkManager;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        playerManager.handleJoin(player.getUniqueId(), player.getName());
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        Optional<Arena> arenaOpt = arenaManager.findArenaByPlayer(uuid);

        playerManager.handleQuit(uuid);
        arenaOpt.ifPresent(arena -> {
            gameManager.handlePlayerQuit(arena, uuid);
            if (arena.getState() == ArenaState.STARTING && !arena.hasMinimumPlayers()) {
                countdownManager.cancelCountdown(arena.getId(), true);
            }
            networkManager.publishArenaStatus(ArenaStatusSnapshot.fromArena(serverId, arena));
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Optional<Arena> victimArena = arenaManager.findArenaByPlayer(victim.getUniqueId());
        if (victimArena.isEmpty()) {
            return;
        }
        Arena arena = victimArena.get();
        if (arena.getState() != ArenaState.INGAME || gameManager.isGracePeriodActive(arena)) {
            event.setCancelled(true);
            return;
        }
        if (event.getDamager() instanceof Player attacker && !arena.containsPlayer(attacker.getUniqueId())) {
            event.setCancelled(true);
        } else if (event.getDamager() instanceof Projectile projectile
                && projectile.getShooter() instanceof Player shooter
                && !arena.containsPlayer(shooter.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Optional<Arena> arenaOpt = arenaManager.findArenaByPlayer(victim.getUniqueId());
        if (arenaOpt.isEmpty()) {
            return;
        }
        Arena arena = arenaOpt.get();
        if (arena.getState() != ArenaState.INGAME) {
            return;
        }
        Player killer = victim.getKiller();
        UUID killerId = killer != null ? killer.getUniqueId() : null;
        gameManager.handlePlayerElimination(arena, victim.getUniqueId(), killerId);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        Optional<Arena> arenaOpt = arenaManager.findArenaByPlayer(player.getUniqueId());
        arenaOpt.ifPresent(arena ->
                arena.getLobbySpawn().toBukkitLocation().ifPresent(event::setRespawnLocation)
        );
    }
}
