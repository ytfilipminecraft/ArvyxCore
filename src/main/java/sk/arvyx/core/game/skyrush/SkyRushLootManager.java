package sk.arvyx.core.game.skyrush;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import sk.arvyx.core.arena.Arena;
import sk.arvyx.core.reset.ArenaBlockTracker;
import sk.arvyx.core.util.Logger;

import java.util.List;
import java.util.Objects;
import java.util.Random;

/**
 * Správca loot truhlíc a Supply Drop udalostí pre herný režim SkyRush.
 */
public final class SkyRushLootManager {

    private final Logger logger;
    private final Random random = new Random();

    public SkyRushLootManager(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "Logger nesmie byť null");
    }

    public int populateArenaChests(Arena arena, SkyRushConfig config) {
        Objects.requireNonNull(arena, "Aréna nesmie byť null");
        Objects.requireNonNull(config, "SkyRushConfig nesmie byť null");

        World world = Bukkit.getWorld(arena.getWorldName());
        if (world == null) {
            logger.debug("Svet '" + arena.getWorldName() + "' nie je načítaný — dopĺňanie truhlíc preskočené.");
            return 0;
        }

        Location center = arena.getCenterLocation().toBukkitLocation(world);
        int scanRadius = config.getChestScanRadius();
        int centerRadiusSq = config.getCenterRadius() * config.getCenterRadius();
        int scanRadiusSq = scanRadius * scanRadius;

        int minChunkX = (center.getBlockX() - scanRadius) >> 4;
        int maxChunkX = (center.getBlockX() + scanRadius) >> 4;
        int minChunkZ = (center.getBlockZ() - scanRadius) >> 4;
        int maxChunkZ = (center.getBlockZ() + scanRadius) >> 4;

        int filledCount = 0;
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                if (!world.isChunkLoaded(cx, cz)) {
                    continue;
                }
                Chunk chunk = world.getChunkAt(cx, cz);
                for (BlockState tileEntity : chunk.getTileEntities()) {
                    if (!(tileEntity instanceof Chest chest)) {
                        continue;
                    }
                    Location loc = chest.getLocation();
                    double dx = loc.getX() - center.getX();
                    double dz = loc.getZ() - center.getZ();
                    double distSq = (dx * dx) + (dz * dz);
                    if (distSq > scanRadiusSq) {
                        continue;
                    }
                    boolean isCenter = distSq <= centerRadiusSq;
                    List<SkyRushConfig.LootEntry> table = isCenter
                            ? config.getCenterLootTable()
                            : config.getIslandLootTable();
                    int minItems = isCenter ? config.getCenterMinItems() : config.getIslandMinItems();
                    int maxItems = isCenter ? config.getCenterMaxItems() : config.getIslandMaxItems();
                    fillInventory(chest.getBlockInventory(), table, minItems, maxItems);
                    filledCount++;
                }
            }
        }
        return filledCount;
    }

    public boolean spawnSupplyDrop(Arena arena, SkyRushConfig config, ArenaBlockTracker blockTracker) {
        World world = Bukkit.getWorld(arena.getWorldName());
        if (world == null) {
            return false;
        }
        Location center = arena.getCenterLocation().toBukkitLocation(world);
        int offsetX = random.nextInt(-3, 4);
        int offsetZ = random.nextInt(-3, 4);
        int targetX = center.getBlockX() + offsetX;
        int targetZ = center.getBlockZ() + offsetZ;
        int y = Math.max(world.getMinHeight() + 5, center.getBlockY());

        Block block = world.getBlockAt(targetX, y, targetZ);
        if (!block.getType().isAir() && block.getType() != Material.CHEST) {
            block = world.getBlockAt(targetX, y + 1, targetZ);
        }
        if (blockTracker != null) {
            blockTracker.recordOriginalBlock(arena.getId(), block);
        }
        block.setType(Material.CHEST, false);
        if (block.getState() instanceof Chest chest) {
            fillInventory(chest.getBlockInventory(), config.getSupplyDropLootTable(), 4, 6);
            return true;
        }
        return false;
    }

    public void fillInventory(
            Inventory inventory,
            List<SkyRushConfig.LootEntry> table,
            int minItems,
            int maxItems
    ) {
        inventory.clear();
        if (table == null || table.isEmpty()) {
            return;
        }
        int totalWeight = 0;
        for (SkyRushConfig.LootEntry entry : table) {
            totalWeight += entry.weight();
        }
        if (totalWeight <= 0) {
            return;
        }

        int itemCount = (maxItems <= minItems)
                ? minItems
                : random.nextInt(minItems, maxItems + 1);
        int size = inventory.getSize();

        for (int i = 0; i < itemCount; i++) {
            SkyRushConfig.LootEntry chosen = pickWeighted(table, totalWeight);
            int amount = (chosen.maxAmount() <= chosen.minAmount())
                    ? chosen.minAmount()
                    : random.nextInt(chosen.minAmount(), chosen.maxAmount() + 1);
            ItemStack stack = new ItemStack(chosen.material(), amount);
            int slot = random.nextInt(size);
            for (int attempt = 0; attempt < 5 && inventory.getItem(slot) != null; attempt++) {
                slot = random.nextInt(size);
            }
            inventory.setItem(slot, stack);
        }
    }

    private SkyRushConfig.LootEntry pickWeighted(List<SkyRushConfig.LootEntry> table, int totalWeight) {
        int roll = random.nextInt(totalWeight);
        int cumulative = 0;
        for (SkyRushConfig.LootEntry entry : table) {
            cumulative += entry.weight();
            if (roll < cumulative) {
                return entry;
            }
        }
        return table.getFirst();
    }
}
