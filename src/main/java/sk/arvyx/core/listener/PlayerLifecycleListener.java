package sk.arvyx.core.listener;

import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import sk.arvyx.core.arena.Arena;
import sk.arvyx.core.arena.ArenaManager;
import sk.arvyx.core.arena.ArenaState;
import sk.arvyx.core.countdown.CountdownManager;
import sk.arvyx.core.game.GameManager;
import sk.arvyx.core.matchmaking.MatchmakingManager;
import sk.arvyx.core.network.ArenaStatusSnapshot;
import sk.arvyx.core.network.NetworkManager;
import sk.arvyx.core.player.PlayerManager;

import java.util.Optional;
import java.util.UUID;

/**
 * Listener životného cyklu hráčov: pripojenie (vrátane rezervácií), odpojenie,
 * ochrana čakajúcich/divákov, plynulá eliminácia bez respawn obrazovky a PvP pravidlá.
 */
public final class PlayerLifecycleListener implements Listener {

    private final String serverId;
    private final ArenaManager arenaManager;
    private final PlayerManager playerManager;
    private final MatchmakingManager matchmakingManager;
    private final GameManager gameManager;
    private final CountdownManager countdownManager;
    private final NetworkManager networkManager;

    public PlayerLifecycleListener(
            String serverId,
            ArenaManager arenaManager,
            PlayerManager playerManager,
            MatchmakingManager matchmakingManager,
            GameManager gameManager,
            CountdownManager countdownManager,
            NetworkManager networkManager
    ) {
        this.serverId = serverId;
        this.arenaManager = arenaManager;
        this.playerManager = playerManager;
        this.matchmakingManager = matchmakingManager;
        this.gameManager = gameManager;
        this.countdownManager = countdownManager;
        this.networkManager = networkManager;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        playerManager.handleJoin(player.getUniqueId(), player.getName());
        if (matchmakingManager != null) {
            matchmakingManager.claimReservationOnJoin(player);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        Optional<Arena> arenaOpt = arenaManager.findArenaByPlayer(uuid);

        ArenaState stateBeforeRemove = arenaOpt.map(Arena::getState).orElse(null);
        boolean wasAlive = arenaOpt.map(a -> a.isPlayerAlive(uuid)).orElse(false);

        playerManager.handleQuit(uuid);
        arenaOpt.ifPresent(arena -> {
            if (stateBeforeRemove == ArenaState.INGAME && wasAlive) {
                gameManager.handlePlayerQuit(arena, uuid);
            } else if (stateBeforeRemove == ArenaState.STARTING && !arena.hasMinimumPlayers()) {
                countdownManager.cancelCountdown(arena.getId(), true);
            }
            networkManager.publishArenaStatus(ArenaStatusSnapshot.fromArena(serverId, arena));
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Optional<Arena> arenaOpt = arenaManager.findArenaByPlayer(victim.getUniqueId());
        if (arenaOpt.isEmpty()) {
            return;
        }
        Arena arena = arenaOpt.get();
        if (arena.getState() != ArenaState.INGAME
                || !arena.isPlayerAlive(victim.getUniqueId())
                || gameManager.isGracePeriodActive(arena)) {
            event.setCancelled(true);
            if (event.getCause() == EntityDamageEvent.DamageCause.VOID && arena.getState() != ArenaState.INGAME) {
                arena.getLobbySpawn().toBukkitLocation().ifPresent(victim::teleport);
            }
        }
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
        if (arena.getState() != ArenaState.INGAME
                || !arena.isPlayerAlive(victim.getUniqueId())
                || gameManager.isGracePeriodActive(arena)) {
            event.setCancelled(true);
            return;
        }
        UUID attackerId = null;
        if (event.getDamager() instanceof Player attacker) {
            attackerId = attacker.getUniqueId();
        } else if (event.getDamager() instanceof Projectile projectile
                && projectile.getShooter() instanceof Player shooter) {
            attackerId = shooter.getUniqueId();
        }
        if (attackerId != null && (!arena.containsPlayer(attackerId) || !arena.isPlayerAlive(attackerId))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFoodLevelChange(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        Optional<Arena> arenaOpt = arenaManager.findArenaByPlayer(player.getUniqueId());
        if (arenaOpt.isEmpty()) {
            return;
        }
        Arena arena = arenaOpt.get();
        if (arena.getState() != ArenaState.INGAME || !arena.isPlayerAlive(player.getUniqueId())) {
            event.setCancelled(true);
            player.setFoodLevel(20);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPickupItem(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        Optional<Arena> arenaOpt = arenaManager.findArenaByPlayer(player.getUniqueId());
        if (arenaOpt.isEmpty()) {
            return;
        }
        Arena arena = arenaOpt.get();
        if (arena.getState() != ArenaState.INGAME || !arena.isPlayerAlive(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        Optional<Arena> arenaOpt = arenaManager.findArenaByPlayer(player.getUniqueId());
        if (arenaOpt.isEmpty()) {
            return;
        }
        Arena arena = arenaOpt.get();
        if (arena.getState() != ArenaState.INGAME || !arena.isPlayerAlive(player.getUniqueId())) {
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
        if (arena.getState() != ArenaState.INGAME || !arena.isPlayerAlive(victim.getUniqueId())) {
            return;
        }

        event.deathMessage(null);
        World world = victim.getWorld();
        if (victim.getLocation().getY() > world.getMinHeight()) {
            for (ItemStack drop : event.getDrops()) {
                if (drop != null && !drop.getType().isAir()) {
                    world.dropItemNaturally(victim.getLocation(), drop);
                }
            }
        }
        event.getDrops().clear();
        event.setDroppedExp(0);
        event.setShouldDropExperience(false);

        AttributeInstance maxHealth = victim.getAttribute(Attribute.MAX_HEALTH);
        double reviveHealth = maxHealth != null ? maxHealth.getValue() : 20.0;
        event.setReviveHealth(reviveHealth);
        event.setCancelled(true);

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
