package sk.arvyx.core.listener;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import sk.arvyx.core.arena.Arena;
import sk.arvyx.core.arena.ArenaManager;
import sk.arvyx.core.arena.ArenaState;
import sk.arvyx.core.reset.ArenaBlockTracker;

import java.util.List;
import java.util.Optional;

/**
 * Chráni čakajúce/resetujúce sa arény pred úpravou blokov, explóziami či rozliatím tekutín
 * a zaznamenáva pôvodné stavy zmenených blokov počas INGAME fázy.
 */
public final class ArenaBlockListener implements Listener {

    private final ArenaManager arenaManager;
    private final ArenaBlockTracker blockTracker;
    private volatile boolean trackModifiedBlocks;

    public ArenaBlockListener(
            ArenaManager arenaManager,
            ArenaBlockTracker blockTracker,
            boolean trackModifiedBlocks
    ) {
        this.arenaManager = arenaManager;
        this.blockTracker = blockTracker;
        this.trackModifiedBlocks = trackModifiedBlocks;
    }

    public void setTrackModifiedBlocks(boolean trackModifiedBlocks) {
        this.trackModifiedBlocks = trackModifiedBlocks;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Optional<Arena> arenaOpt = arenaManager.findArenaByPlayer(player.getUniqueId());
        if (arenaOpt.isEmpty()) {
            return;
        }
        Arena arena = arenaOpt.get();
        if (arena.getState() != ArenaState.INGAME || !arena.isPlayerAlive(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        if (trackModifiedBlocks) {
            blockTracker.recordOriginalBlock(arena.getId(), event.getBlock());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        Optional<Arena> arenaOpt = arenaManager.findArenaByPlayer(player.getUniqueId());
        if (arenaOpt.isEmpty()) {
            return;
        }
        Arena arena = arenaOpt.get();
        if (arena.getState() != ArenaState.INGAME || !arena.isPlayerAlive(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        if (trackModifiedBlocks) {
            blockTracker.recordOriginalState(
                    arena.getId(),
                    event.getBlock().getWorld().getName(),
                    event.getBlock().getX(),
                    event.getBlock().getY(),
                    event.getBlock().getZ(),
                    event.getBlockReplacedState().getBlockData().getAsString()
            );
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        handleExplosionBlocks(event.getLocation().getWorld().getName(), event.blockList());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        handleExplosionBlocks(event.getBlock().getWorld().getName(), event.blockList());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent event) {
        String worldName = event.getBlock().getWorld().getName();
        for (Arena arena : arenaManager.getAllArenas()) {
            if (!arena.getWorldName().equalsIgnoreCase(worldName)) {
                continue;
            }
            if (arena.getState() != ArenaState.INGAME && arena.getState() != ArenaState.DISABLED) {
                event.setCancelled(true);
                return;
            }
            if (trackModifiedBlocks && arena.getState() == ArenaState.INGAME) {
                blockTracker.recordOriginalBlock(arena.getId(), event.getBlock());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockFromTo(BlockFromToEvent event) {
        String worldName = event.getBlock().getWorld().getName();
        for (Arena arena : arenaManager.getAllArenas()) {
            if (!arena.getWorldName().equalsIgnoreCase(worldName)) {
                continue;
            }
            if (arena.getState() != ArenaState.INGAME && arena.getState() != ArenaState.DISABLED) {
                event.setCancelled(true);
                return;
            }
            if (trackModifiedBlocks && arena.getState() == ArenaState.INGAME) {
                blockTracker.recordOriginalBlock(arena.getId(), event.getToBlock());
            }
        }
    }

    private void handleExplosionBlocks(String worldName, List<Block> blocks) {
        if (blocks.isEmpty()) {
            return;
        }
        boolean hasProtectedArena = false;
        boolean hasActiveIngameArena = false;
        for (Arena arena : arenaManager.getAllArenas()) {
            if (!arena.getWorldName().equalsIgnoreCase(worldName)) {
                continue;
            }
            if (arena.getState() == ArenaState.INGAME) {
                hasActiveIngameArena = true;
                if (trackModifiedBlocks) {
                    for (Block block : blocks) {
                        blockTracker.recordOriginalBlock(arena.getId(), block);
                    }
                }
            } else if (arena.getState() != ArenaState.DISABLED) {
                hasProtectedArena = true;
            }
        }
        if (hasProtectedArena && !hasActiveIngameArena) {
            blocks.clear();
        }
    }
}
