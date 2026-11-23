package sk.arvyx.core.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import sk.arvyx.core.arena.Arena;
import sk.arvyx.core.arena.ArenaLocation;
import sk.arvyx.core.arena.ArenaType;
import sk.arvyx.core.network.RedisConfig;
import sk.arvyx.core.util.Logger;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Centralizovaná správa a validácia všetkých YAML konfigurácií pluginu ArvyxCore 1.1.0.
 * Načítava iba arény priradené ku konkrétnemu server.id.
 */
public final class ConfigManager {

    private final JavaPlugin plugin;
    private final Logger logger;

    private FileConfiguration config;
    private FileConfiguration arenasConfig;
    private FileConfiguration messagesConfig;
    private FileConfiguration skyRushConfig;
    private FileConfiguration chaosConfig;

    public ConfigManager(JavaPlugin plugin, Logger logger) {
        this.plugin = Objects.requireNonNull(plugin, "Plugin nesmie byť null");
        this.logger = Objects.requireNonNull(logger, "Logger nesmie byť null");
    }

    public void loadAll() {
        ensureDefaultResources();

        plugin.reloadConfig();
        this.config = plugin.getConfig();
        this.arenasConfig = loadYaml("arenas.yml");
        this.messagesConfig = loadYaml("messages.yml");
        this.skyRushConfig = loadYaml("games/skyrush.yml");
        this.chaosConfig = loadYaml("games/chaos.yml");

        validateMainConfig();
        logger.setDebugEnabled(isDebugLogging());
        logger.info("Konfigurácie (config, arenas, messages, skyrush, chaos) boli načítané a zvalidované.");
    }

    private void ensureDefaultResources() {
        plugin.saveDefaultConfig();
        saveResourceIfMissing("arenas.yml");
        saveResourceIfMissing("messages.yml");
        saveResourceIfMissing("games/skyrush.yml");
        saveResourceIfMissing("games/chaos.yml");
    }

    private void saveResourceIfMissing(String resourcePath) {
        File target = new File(plugin.getDataFolder(), resourcePath);
        if (!target.exists()) {
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                logger.warn("Nepodarilo sa vytvoriť priečinok pre konfiguráciu: " + parent.getPath());
            }
            plugin.saveResource(resourcePath, false);
        }
    }

    private FileConfiguration loadYaml(String relativePath) {
        File file = new File(plugin.getDataFolder(), relativePath);
        YamlConfiguration loaded = YamlConfiguration.loadConfiguration(file);
        try (InputStream defaultStream = plugin.getResource(relativePath)) {
            if (defaultStream != null) {
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(defaultStream, StandardCharsets.UTF_8)
                );
                loaded.setDefaults(defaults);
            }
        } catch (Exception ex) {
            logger.error("Nepodarilo sa načítať predvolený súbor " + relativePath, ex);
        }
        return loaded;
    }

    private void validateMainConfig() {
        if (getServerId().isBlank()) {
            logger.warn("server.id je prázdne — použije sa predvolená hodnota 'standalone'.");
        }
        if (getLobbyServer().isBlank()) {
            logger.warn("network.lobby-server je prázdne — použije sa predvolená hodnota 'lobby'.");
        }
        if (getDefaultCountdownSeconds() < 1) {
            logger.warn("countdown.default-seconds musí byť >= 1. Použije sa 15.");
        }
        if (getEndingDelaySeconds() < 1) {
            logger.warn("reset.ending-delay-seconds musí byť >= 1. Použije sa 5.");
        }
    }

    /**
     * Čisté pravidlo vlastníctva arény: aréna patrí len na server so zhodným server.id.
     */
    public static boolean shouldLoadArenaForServer(String arenaServerId, String localServerId) {
        if (arenaServerId == null || arenaServerId.isBlank() || localServerId == null || localServerId.isBlank()) {
            return false;
        }
        return arenaServerId.trim().equalsIgnoreCase(localServerId.trim());
    }

    public List<Arena> loadConfiguredArenas() {
        String localServerId = getServerId();
        ConfigurationSection section = arenasConfig.getConfigurationSection("arenas");
        if (section == null) {
            logger.warn("Sekcia 'arenas' v arenas.yml chýba.");
            return List.of();
        }
        List<Arena> result = new ArrayList<>();
        int skippedOtherServers = 0;

        for (String arenaId : section.getKeys(false)) {
            ConfigurationSection arenaSec = section.getConfigurationSection(arenaId);
            if (arenaSec == null) {
                continue;
            }
            String ownerServer = arenaSec.getString("server", "");
            if (ownerServer == null || ownerServer.isBlank()) {
                logger.warn("Aréna '" + arenaId + "' nemá definované pole 'server' v arenas.yml — preskočená.");
                continue;
            }
            if (!shouldLoadArenaForServer(ownerServer, localServerId)) {
                skippedOtherServers++;
                logger.debug("Aréna '" + arenaId + "' patrí serveru '" + ownerServer
                        + "' (lokálny server='" + localServerId + "') — preskočená.");
                continue;
            }

            try {
                String name = arenaSec.getString("name", arenaId);
                String typeRaw = arenaSec.getString("type", "");
                ArenaType type = ArenaType.fromString(typeRaw)
                        .orElseThrow(() -> new IllegalArgumentException("Neplatný typ arény: " + typeRaw));
                boolean enabled = arenaSec.getBoolean("enabled", true);
                String worldName = arenaSec.getString("world", "world");
                if (worldName == null || worldName.isBlank()) {
                    throw new IllegalArgumentException("Chýba názov sveta (world)");
                }
                int minPlayers = Math.max(1, arenaSec.getInt("min-players", 2));
                int maxPlayers = Math.max(minPlayers, arenaSec.getInt("max-players", 8));
                int countdown = Math.max(1, arenaSec.getInt("countdown-seconds", getDefaultCountdownSeconds()));
                int matchDuration = Math.max(30, arenaSec.getInt("match-duration-seconds", 240));

                ArenaLocation lobbySpawn = readLocation(
                        arenaSec.getConfigurationSection("lobby-spawn"), worldName, 0.5, 80.0, 0.5
                );
                ArenaLocation center = readLocation(
                        arenaSec.getConfigurationSection("center"), worldName, 0.5, 65.0, 0.5
                );
                List<ArenaLocation> spawns = new ArrayList<>();
                for (var map : arenaSec.getMapList("spawns")) {
                    double x = toDouble(map.get("x"), center.x());
                    double y = toDouble(map.get("y"), center.y());
                    double z = toDouble(map.get("z"), center.z());
                    float yaw = (float) toDouble(map.get("yaw"), 0.0);
                    float pitch = (float) toDouble(map.get("pitch"), 0.0);
                    spawns.add(new ArenaLocation(worldName, x, y, z, yaw, pitch));
                }

                result.add(new Arena(
                        arenaId,
                        name,
                        type,
                        worldName,
                        minPlayers,
                        maxPlayers,
                        countdown,
                        matchDuration,
                        lobbySpawn,
                        center,
                        spawns,
                        enabled
                ));
            } catch (Exception ex) {
                logger.error("Chyba pri načítaní arény '" + arenaId + "' z arenas.yml: " + ex.getMessage());
            }
        }

        logger.info("Pre server '" + localServerId + "' načítaných " + result.size()
                + " lokálnych arén (preskočených pre iné servery: " + skippedOtherServers + ").");
        return Collections.unmodifiableList(result);
    }

    private static ArenaLocation readLocation(
            ConfigurationSection sec,
            String worldName,
            double defX,
            double defY,
            double defZ
    ) {
        if (sec == null) {
            return new ArenaLocation(worldName, defX, defY, defZ);
        }
        return new ArenaLocation(
                worldName,
                sec.getDouble("x", defX),
                sec.getDouble("y", defY),
                sec.getDouble("z", defZ),
                (float) sec.getDouble("yaw", 0.0),
                (float) sec.getDouble("pitch", 0.0)
        );
    }

    private static double toDouble(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String str) {
            try {
                return Double.parseDouble(str);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public Material parseMaterial(String name, Material fallback) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        Material mat = Material.matchMaterial(name.trim());
        if (mat == null) {
            logger.warn("Neznámy materiál v konfigurácii: '" + name + "', použitý fallback " + fallback.name());
            return fallback;
        }
        return mat;
    }

    public String getServerId() {
        return config.getString("server.id", "standalone");
    }

    public String getServerRole() {
        return config.getString("server.role", "STANDALONE");
    }

    public String getServerDisplayName() {
        return config.getString("server.display-name", getServerId());
    }

    public String getLobbyServer() {
        return config.getString("network.lobby-server", "lobby");
    }

    public int getHeartbeatIntervalSeconds() {
        return Math.max(3, config.getInt("network.heartbeat-interval-seconds", 10));
    }

    public int getServerTimeoutSeconds() {
        return Math.max(10, config.getInt("network.server-timeout-seconds", 30));
    }

    public boolean isRedisEnabled() {
        return config.getBoolean("network.redis.enabled", false);
    }

    public RedisConfig getRedisConfig() {
        String envVar = config.getString("network.redis.password-env", "ARVYX_REDIS_PASSWORD");
        String password = (envVar != null && !envVar.isBlank()) ? System.getenv(envVar) : "";
        return new RedisConfig(
                isRedisEnabled(),
                config.getString("network.redis.host", "127.0.0.1"),
                config.getInt("network.redis.port", 6379),
                config.getBoolean("network.redis.ssl", false),
                config.getInt("network.redis.timeout-ms", 2000),
                password,
                config.getString("network.redis.channel-prefix", "arvyx:network"),
                getServerTimeoutSeconds()
        );
    }

    public boolean isUseVelocityTransfer() {
        return config.getBoolean("lobby.use-velocity-transfer", true);
    }

    public ArenaLocation getFallbackLobbySpawn() {
        ConfigurationSection sec = config.getConfigurationSection("lobby.fallback-spawn");
        if (sec == null) {
            return new ArenaLocation("world", 0.5, 65.0, 0.5);
        }
        String world = sec.getString("world", "world");
        if (world == null || world.isBlank()) {
            world = "world";
        }
        return new ArenaLocation(
                world,
                sec.getDouble("x", 0.5),
                sec.getDouble("y", 65.0),
                sec.getDouble("z", 0.5),
                (float) sec.getDouble("yaw", 0.0),
                (float) sec.getDouble("pitch", 0.0)
        );
    }

    public int getDefaultCountdownSeconds() {
        return Math.max(1, config.getInt("countdown.default-seconds", 15));
    }

    public List<Integer> getCountdownNotifyIntervals() {
        List<Integer> list = config.getIntegerList("countdown.notify-intervals");
        return list.isEmpty() ? List.of(30, 15, 10, 5, 4, 3, 2, 1) : List.copyOf(list);
    }

    public int getEndingDelaySeconds() {
        return Math.max(1, config.getInt("reset.ending-delay-seconds", 5));
    }

    public boolean isRestorePlayerStateOnReset() {
        return config.getBoolean("reset.restore-player-state", true);
    }

    public boolean isClearTemporaryEntitiesOnReset() {
        return config.getBoolean("reset.clear-temporary-entities", true);
    }

    public boolean isRestoreModifiedBlocksOnReset() {
        return config.getBoolean("reset.restore-modified-blocks", true);
    }

    public boolean isWorldBackupEnabled() {
        return config.getBoolean("reset.world-backup.enabled", false);
    }

    public boolean isAllowWorldReplacement() {
        return config.getBoolean("reset.world-backup.allow-world-replacement", false);
    }

    public String getWorldBackupsDir() {
        String dir = config.getString("reset.world-backup.backups-dir", "backups");
        return (dir == null || dir.isBlank()) ? "backups" : dir.trim();
    }

    public boolean isDebugLogging() {
        return config.getBoolean("logging.debug", false);
    }

    public String getPrefix() {
        return messagesConfig.getString("prefix", "<gradient:#8A2BE2:#4169E1>[ARVYX]</gradient>");
    }

    public String getMessage(String path) {
        return getMessage(path, "<prefix> <#94A3B8>(" + path + ")");
    }

    public String getMessage(String path, String fallback) {
        String value = messagesConfig.getString(path);
        return (value == null || value.isBlank()) ? fallback : value;
    }

    public FileConfiguration getSkyRushConfig() {
        return skyRushConfig;
    }

    public FileConfiguration getChaosConfig() {
        return chaosConfig;
    }

    public FileConfiguration getMainConfig() {
        return config;
    }
}
