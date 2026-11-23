package sk.arvyx.core.player;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import sk.arvyx.core.arena.Arena;
import sk.arvyx.core.arena.ArenaLocation;
import sk.arvyx.core.arena.ArenaManager;
import sk.arvyx.core.util.Logger;
import sk.arvyx.core.util.MessageFormatter;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Správca hráčov pracujúci s UUID ako primárnym identifikátorom.
 * Chráni pred duplicitným priradením hráča do viacerých arén a rieši bezpečný návrat na lobby.
 */
public final class PlayerManager {

    public static final String BUNGEECORD_CHANNEL = "BungeeCord";

    private final ArenaManager arenaManager;
    private final Logger logger;
    private final Map<UUID, PlayerData> playerDataMap = new ConcurrentHashMap<>();

    private JavaPlugin plugin;
    private MessageFormatter messageFormatter;
    private String localServerId = "standalone";
    private String lobbyServerName = "lobby";
    private boolean useVelocityTransfer = true;
    private ArenaLocation fallbackLobbySpawn = new ArenaLocation("world", 0.5, 65.0, 0.5);
    private String returningLobbyMessage = "<prefix> <#60A5FA>Presúvame ťa späť na lobby server...";

    public PlayerManager(ArenaManager arenaManager, Logger logger) {
        this.arenaManager = Objects.requireNonNull(arenaManager, "ArenaManager nesmie byť null");
        this.logger = Objects.requireNonNull(logger, "Logger nesmie byť null");
    }

    public void attachRuntime(
            JavaPlugin plugin,
            MessageFormatter messageFormatter,
            String localServerId,
            String lobbyServerName,
            boolean useVelocityTransfer,
            ArenaLocation fallbackLobbySpawn,
            String returningLobbyMessage
    ) {
        this.plugin = plugin;
        this.messageFormatter = messageFormatter;
        this.localServerId = (localServerId == null || localServerId.isBlank()) ? "standalone" : localServerId.trim();
        updateLobbySettings(lobbyServerName, useVelocityTransfer, fallbackLobbySpawn, returningLobbyMessage);
    }

    public void updateLobbySettings(
            String lobbyServerName,
            boolean useVelocityTransfer,
            ArenaLocation fallbackLobbySpawn,
            String returningLobbyMessage
    ) {
        this.lobbyServerName = (lobbyServerName == null || lobbyServerName.isBlank()) ? "lobby" : lobbyServerName.trim();
        this.useVelocityTransfer = useVelocityTransfer;
        if (fallbackLobbySpawn != null) {
            this.fallbackLobbySpawn = fallbackLobbySpawn;
        }
        if (returningLobbyMessage != null && !returningLobbyMessage.isBlank()) {
            this.returningLobbyMessage = returningLobbyMessage;
        }
    }

    public PlayerData handleJoin(UUID uuid, String playerName) {
        Objects.requireNonNull(uuid, "UUID nesmie byť null");
        PlayerData data = playerDataMap.compute(uuid, (id, existing) -> {
            if (existing == null) {
                return new PlayerData(id, playerName);
            }
            existing.setLastKnownName(playerName);
            existing.markJoinedNow();
            return existing;
        });
        logger.debug("Registrované dáta hráča UUID=" + uuid + " (" + data.getLastKnownName() + ")");
        return data;
    }

    public void handleQuit(UUID uuid) {
        if (uuid == null) {
            return;
        }
        removePlayerFromArena(uuid);
        PlayerData data = playerDataMap.get(uuid);
        if (data != null) {
            data.setAlive(false);
            data.setCurrentArenaId(null);
        }
    }

    /**
     * Bezpečne priradí hráča do arény. Zabraňuje duplicitnému priradeniu do viacerých arén.
     */
    public synchronized JoinResult assignPlayerToArena(UUID uuid, Arena targetArena) {
        Objects.requireNonNull(uuid, "UUID nesmie byť null");
        Objects.requireNonNull(targetArena, "Aréna nesmie byť null");

        PlayerData data = playerDataMap.computeIfAbsent(uuid, id -> new PlayerData(id, id.toString()));
        Optional<String> existingArenaId = data.getCurrentArenaId();
        if (existingArenaId.isPresent()) {
            if (existingArenaId.get().equalsIgnoreCase(targetArena.getId())) {
                return JoinResult.ALREADY_IN_THIS_ARENA;
            }
            return JoinResult.ALREADY_IN_OTHER_ARENA;
        }

        // Dodatočná ochrana cez ArenaManager pre prípad nekonzistentného stavu
        if (arenaManager.findArenaByPlayer(uuid).isPresent()) {
            return JoinResult.ALREADY_IN_OTHER_ARENA;
        }

        if (!targetArena.getState().isJoinable()) {
            return JoinResult.ARENA_NOT_JOINABLE;
        }
        if (targetArena.isFull()) {
            return JoinResult.ARENA_FULL;
        }
        if (!targetArena.addPlayer(uuid)) {
            return JoinResult.ARENA_NOT_JOINABLE;
        }

        data.setCurrentArenaId(targetArena.getId());
        data.setAlive(true);
        return JoinResult.SUCCESS;
    }

    public synchronized Optional<Arena> removePlayerFromArena(UUID uuid) {
        if (uuid == null) {
            return Optional.empty();
        }
        PlayerData data = playerDataMap.get(uuid);
        Optional<Arena> arenaOpt = Optional.empty();

        if (data != null && data.getCurrentArenaId().isPresent()) {
            arenaOpt = arenaManager.getArena(data.getCurrentArenaId().get());
            data.setCurrentArenaId(null);
            data.setAlive(false);
        }

        if (arenaOpt.isEmpty()) {
            arenaOpt = arenaManager.findArenaByPlayer(uuid);
        }

        arenaOpt.ifPresent(arena -> arena.removePlayer(uuid));
        return arenaOpt;
    }

    public synchronized void clearArenaAssignment(UUID uuid) {
        if (uuid == null) {
            return;
        }
        PlayerData data = playerDataMap.get(uuid);
        if (data != null) {
            data.setCurrentArenaId(null);
            data.setAlive(false);
        }
    }

    public Optional<PlayerData> getPlayerData(UUID uuid) {
        return uuid == null ? Optional.empty() : Optional.ofNullable(playerDataMap.get(uuid));
    }

    public Optional<String> getCurrentArenaId(UUID uuid) {
        return getPlayerData(uuid).flatMap(PlayerData::getCurrentArenaId);
    }

    public boolean isInArena(UUID uuid) {
        return getCurrentArenaId(uuid).isPresent();
    }

    public Collection<PlayerData> getTrackedPlayers() {
        return Collections.unmodifiableCollection(List.copyOf(playerDataMap.values()));
    }

    public int getTrackedPlayerCount() {
        return playerDataMap.size();
    }

    /**
     * Obnoví čistý herný stav hráča bez zostatkových efektov alebo upravenej gravitácie.
     */
    public void restoreCleanPlayerState(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        player.getInventory().clear();
        player.getInventory().setArmorContents(null);
        player.setItemOnCursor(null);
        for (PotionEffect effect : player.getActivePotionEffects()) {
            player.removePotionEffect(effect.getType());
        }
        AttributeInstance gravity = player.getAttribute(Attribute.GRAVITY);
        if (gravity != null && plugin != null) {
            gravity.removeModifier(new org.bukkit.NamespacedKey(plugin, "chaos_low_gravity"));
        }
        player.setFireTicks(0);
        player.setFallDistance(0.0f);
        player.setFoodLevel(20);
        player.setSaturation(20.0f);
        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        double targetHealth = maxHealth != null ? maxHealth.getValue() : 20.0;
        player.setHealth(targetHealth);
        player.setExp(0.0f);
        player.setLevel(0);
        player.setAllowFlight(false);
        player.setFlying(false);
        player.setGameMode(GameMode.SURVIVAL);
    }

    /**
     * Presunie hráča na iný server v rámci Velocity siete cez kanál BungeeCord.
     */
    public boolean transferToServer(Player player, String targetServer) {
        if (player == null || !player.isOnline() || targetServer == null || targetServer.isBlank()) {
            return false;
        }
        if (!useVelocityTransfer || plugin == null || !plugin.isEnabled()) {
            return false;
        }
        try {
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            out.writeUTF("Connect");
            out.writeUTF(targetServer.trim());
            player.sendPluginMessage(plugin, BUNGEECORD_CHANNEL, out.toByteArray());
            return true;
        } catch (Exception ex) {
            logger.warn("Presun hráča " + player.getName() + " na server '" + targetServer + "' zlyhal: " + ex.getMessage());
            return false;
        }
    }

    /**
     * Bezpečne vráti hráča späť do lobby cez Velocity BungeeCord kanál alebo fallback teleport.
     */
    public void returnToLobby(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        removePlayerFromArena(player.getUniqueId());
        restoreCleanPlayerState(player);

        if (messageFormatter != null) {
            messageFormatter.send(player, returningLobbyMessage);
        }

        if (!localServerId.equalsIgnoreCase(lobbyServerName) && transferToServer(player, lobbyServerName)) {
            return;
        }

        fallbackLobbySpawn.toBukkitLocation().ifPresentOrElse(
                player::teleport,
                () -> {
                    var defaultWorld = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().getFirst();
                    if (defaultWorld != null) {
                        player.teleport(defaultWorld.getSpawnLocation());
                    }
                }
        );
    }

    public void shutdown() {
        playerDataMap.clear();
    }

    public enum JoinResult {
        SUCCESS,
        ALREADY_IN_THIS_ARENA,
        ALREADY_IN_OTHER_ARENA,
        ARENA_NOT_JOINABLE,
        ARENA_FULL
    }
}
