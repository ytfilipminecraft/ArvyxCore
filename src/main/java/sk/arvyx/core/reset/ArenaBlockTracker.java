package sk.arvyx.core.reset;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import sk.arvyx.core.arena.Arena;
import sk.arvyx.core.util.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sleduje pôvodné stavy blokov zmenených počas aktívneho zápasu v aréne
 * a bezpečne ich vracia späť pri resete (vrátane odstránenia dočasných stavieb).
 */
public final class ArenaBlockTracker {

    public record BlockCoord(String worldName, int x, int y, int z) {
        public BlockCoord {
            Objects.requireNonNull(worldName, "worldName nesmie byť null");
        }
    }

    public record BlockSnapshot(BlockCoord coord, String originalBlockData) {
        public BlockSnapshot {
            Objects.requireNonNull(coord, "coord nesmie byť null");
            Objects.requireNonNull(originalBlockData, "originalBlockData nesmie byť null");
        }
    }

    private final Logger logger;
    private final Map<String, Map<BlockCoord, BlockSnapshot>> trackedByArena = new ConcurrentHashMap<>();

    public ArenaBlockTracker(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "Logger nesmie byť null");
    }

    /**
     * Zaznamená pôvodný stav bloku pred jeho prvou zmenou v rámci aktuálnej hernej relácie.
     * Opakované zmeny na tej istej súradnici pôvodný záznam neprepíšu.
     */
    public void recordOriginalState(String arenaId, String worldName, int x, int y, int z, String originalBlockData) {
        if (arenaId == null || worldName == null || originalBlockData == null) {
            return;
        }
        String key = normalizeKey(arenaId);
        Map<BlockCoord, BlockSnapshot> arenaMap = trackedByArena.computeIfAbsent(
                key,
                ignored -> Collections.synchronizedMap(new LinkedHashMap<>())
        );
        BlockCoord coord = new BlockCoord(worldName, x, y, z);
        arenaMap.putIfAbsent(coord, new BlockSnapshot(coord, originalBlockData));
    }

    public void recordOriginalBlock(String arenaId, Block block) {
        if (arenaId == null || block == null) {
            return;
        }
        recordOriginalState(
                arenaId,
                block.getWorld().getName(),
                block.getX(),
                block.getY(),
                block.getZ(),
                block.getBlockData().getAsString()
        );
    }

    public int getTrackedBlockCount(String arenaId) {
        if (arenaId == null) {
            return 0;
        }
        Map<BlockCoord, BlockSnapshot> arenaMap = trackedByArena.get(normalizeKey(arenaId));
        return arenaMap == null ? 0 : arenaMap.size();
    }

    public List<BlockSnapshot> drainSnapshotsInRestoreOrder(String arenaId) {
        if (arenaId == null) {
            return List.of();
        }
        Map<BlockCoord, BlockSnapshot> arenaMap = trackedByArena.remove(normalizeKey(arenaId));
        if (arenaMap == null || arenaMap.isEmpty()) {
            return List.of();
        }
        List<BlockSnapshot> list;
        synchronized (arenaMap) {
            list = new ArrayList<>(arenaMap.values());
        }
        Collections.reverse(list);
        return Collections.unmodifiableList(list);
    }

    public int restoreArenaBlocks(Arena arena) {
        if (arena == null) {
            return 0;
        }
        List<BlockSnapshot> snapshots = drainSnapshotsInRestoreOrder(arena.getId());
        if (snapshots.isEmpty()) {
            return 0;
        }
        World world = Bukkit.getWorld(arena.getWorldName());
        if (world == null) {
            logger.warn("Svet '" + arena.getWorldName() + "' nie je načítaný — obnova " + snapshots.size()
                    + " blokov arény '" + arena.getId() + "' preskočená.");
            return 0;
        }

        int restored = 0;
        for (BlockSnapshot snapshot : snapshots) {
            BlockCoord c = snapshot.coord();
            if (!c.worldName().equalsIgnoreCase(world.getName())) {
                continue;
            }
            try {
                BlockData data = Bukkit.createBlockData(snapshot.originalBlockData());
                world.getBlockAt(c.x(), c.y(), c.z()).setBlockData(data, false);
                restored++;
            } catch (Exception ex) {
                logger.warn("Nepodarilo sa obnoviť blok na [" + c.x() + "," + c.y() + "," + c.z()
                        + "]: " + ex.getMessage());
            }
        }
        logger.debug("Obnovených " + restored + " zmenených blokov v aréne '" + arena.getId() + "'.");
        return restored;
    }

    public void clearArena(String arenaId) {
        if (arenaId != null) {
            trackedByArena.remove(normalizeKey(arenaId));
        }
    }

    public void clearAll() {
        trackedByArena.clear();
    }

    private static String normalizeKey(String id) {
        return id.trim().toLowerCase(Locale.ROOT);
    }
}
