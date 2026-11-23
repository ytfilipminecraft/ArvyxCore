package sk.arvyx.core.game.skyrush;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import sk.arvyx.core.config.ConfigManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Konfiguračný model pre herný režim SkyRush načítaný z games/skyrush.yml.
 */
public final class SkyRushConfig {

    public record LootEntry(Material material, int minAmount, int maxAmount, int weight) {
        public LootEntry {
            if (minAmount < 1) {
                minAmount = 1;
            }
            if (maxAmount < minAmount) {
                maxAmount = minAmount;
            }
            if (weight < 1) {
                weight = 1;
            }
        }
    }

    public record KitItem(Material material, int amount) {
    }

    private final int matchDurationSeconds;
    private final int gracePeriodSeconds;
    private final int supplyDropIntervalSeconds;
    private final int chestRefillSeconds;
    private final double voidYLevel;
    private final int chestScanRadius;
    private final int centerRadius;
    private final int islandMinItems;
    private final int islandMaxItems;
    private final int centerMinItems;
    private final int centerMaxItems;
    private final List<KitItem> starterKit;
    private final List<LootEntry> islandLootTable;
    private final List<LootEntry> centerLootTable;
    private final List<LootEntry> supplyDropLootTable;

    private SkyRushConfig(
            int matchDurationSeconds,
            int gracePeriodSeconds,
            int supplyDropIntervalSeconds,
            int chestRefillSeconds,
            double voidYLevel,
            int chestScanRadius,
            int centerRadius,
            int islandMinItems,
            int islandMaxItems,
            int centerMinItems,
            int centerMaxItems,
            List<KitItem> starterKit,
            List<LootEntry> islandLootTable,
            List<LootEntry> centerLootTable,
            List<LootEntry> supplyDropLootTable
    ) {
        this.matchDurationSeconds = matchDurationSeconds;
        this.gracePeriodSeconds = gracePeriodSeconds;
        this.supplyDropIntervalSeconds = supplyDropIntervalSeconds;
        this.chestRefillSeconds = chestRefillSeconds;
        this.voidYLevel = voidYLevel;
        this.chestScanRadius = chestScanRadius;
        this.centerRadius = centerRadius;
        this.islandMinItems = islandMinItems;
        this.islandMaxItems = islandMaxItems;
        this.centerMinItems = centerMinItems;
        this.centerMaxItems = centerMaxItems;
        this.starterKit = List.copyOf(starterKit);
        this.islandLootTable = List.copyOf(islandLootTable);
        this.centerLootTable = List.copyOf(centerLootTable);
        this.supplyDropLootTable = List.copyOf(supplyDropLootTable);
    }

    public static SkyRushConfig fromConfig(ConfigManager configManager) {
        FileConfiguration cfg = configManager.getSkyRushConfig();
        int matchDuration = Math.clamp(cfg.getInt("skyrush.match-duration-seconds", 240), 60, 600);
        int gracePeriod = Math.clamp(cfg.getInt("skyrush.grace-period-seconds", 10), 0, 60);
        int supplyInterval = Math.clamp(cfg.getInt("skyrush.supply-drop-interval-seconds", 75), 15, 300);
        int chestRefill = Math.clamp(cfg.getInt("skyrush.chest-refill-seconds", 120), 20, 300);
        double voidY = cfg.getDouble("skyrush.void-y-level", 0.0);
        int scanRadius = Math.clamp(cfg.getInt("skyrush.chest-scan-radius", 60), 16, 160);
        int centerRadius = Math.clamp(cfg.getInt("skyrush.center-radius", 14), 4, 48);

        int islandMin = Math.max(1, cfg.getInt("skyrush.loot.island-min-items", 4));
        int islandMax = Math.max(islandMin, cfg.getInt("skyrush.loot.island-max-items", 7));
        int centerMin = Math.max(1, cfg.getInt("skyrush.loot.center-min-items", 5));
        int centerMax = Math.max(centerMin, cfg.getInt("skyrush.loot.center-max-items", 9));

        List<KitItem> kit = readKit(cfg.getMapList("skyrush.starter-kit"), configManager);
        List<LootEntry> islandLoot = readLoot(cfg.getMapList("skyrush.loot.island-table"), configManager);
        List<LootEntry> centerLoot = readLoot(cfg.getMapList("skyrush.loot.center-table"), configManager);
        List<LootEntry> supplyLoot = readLoot(cfg.getMapList("skyrush.loot.supply-drop-table"), configManager);

        return new SkyRushConfig(
                matchDuration,
                gracePeriod,
                supplyInterval,
                chestRefill,
                voidY,
                scanRadius,
                centerRadius,
                islandMin,
                islandMax,
                centerMin,
                centerMax,
                kit,
                islandLoot,
                centerLoot,
                supplyLoot
        );
    }

    private static List<KitItem> readKit(List<Map<?, ?>> rawList, ConfigManager configManager) {
        List<KitItem> list = new ArrayList<>();
        for (Map<?, ?> entry : rawList) {
            Object matObj = entry.get("material");
            if (matObj == null) {
                continue;
            }
            Material material = configManager.parseMaterial(String.valueOf(matObj), Material.STONE_SWORD);
            int amount = entry.get("amount") instanceof Number num ? Math.clamp(num.intValue(), 1, 64) : 1;
            list.add(new KitItem(material, amount));
        }
        return list;
    }

    private static List<LootEntry> readLoot(List<Map<?, ?>> rawList, ConfigManager configManager) {
        List<LootEntry> list = new ArrayList<>();
        for (Map<?, ?> entry : rawList) {
            Object matObj = entry.get("material");
            if (matObj == null) {
                continue;
            }
            Material material = configManager.parseMaterial(String.valueOf(matObj), Material.GOLDEN_APPLE);
            int min = entry.get("min") instanceof Number num ? Math.max(1, num.intValue()) : 1;
            int max = entry.get("max") instanceof Number num ? Math.max(min, num.intValue()) : min;
            int weight = entry.get("weight") instanceof Number num ? Math.max(1, num.intValue()) : 10;
            list.add(new LootEntry(material, min, max, weight));
        }
        return list;
    }

    public int getMatchDurationSeconds() {
        return matchDurationSeconds;
    }

    public int getGracePeriodSeconds() {
        return gracePeriodSeconds;
    }

    public int getSupplyDropIntervalSeconds() {
        return supplyDropIntervalSeconds;
    }

    public int getChestRefillSeconds() {
        return chestRefillSeconds;
    }

    public double getVoidYLevel() {
        return voidYLevel;
    }

    public int getChestScanRadius() {
        return chestScanRadius;
    }

    public int getCenterRadius() {
        return centerRadius;
    }

    public int getIslandMinItems() {
        return islandMinItems;
    }

    public int getIslandMaxItems() {
        return islandMaxItems;
    }

    public int getCenterMinItems() {
        return centerMinItems;
    }

    public int getCenterMaxItems() {
        return centerMaxItems;
    }

    public List<KitItem> getStarterKit() {
        return starterKit;
    }

    public List<LootEntry> getIslandLootTable() {
        return islandLootTable;
    }

    public List<LootEntry> getCenterLootTable() {
        return centerLootTable;
    }

    public List<LootEntry> getSupplyDropLootTable() {
        return supplyDropLootTable;
    }
}
