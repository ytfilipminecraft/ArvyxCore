package sk.arvyx.core.arena;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Reprezentácia jednej hernej arény v systéme ArvyxCore.
 * Všetky prechody stavov sú vláknovo bezpečné a overované voči stavovému automatu.
 */
public final class Arena {

    private final String id;
    private final String name;
    private final ArenaType type;
    private final String worldName;
    private final int minPlayers;
    private final int maxPlayers;
    private final int countdownSeconds;
    private final int matchDurationSeconds;
    private final ArenaLocation lobbySpawn;
    private final ArenaLocation centerLocation;
    private final List<ArenaLocation> spawnPoints;

    private final Set<UUID> players = new LinkedHashSet<>();
    private final Set<UUID> alivePlayers = new LinkedHashSet<>();

    private ArenaState state;
    private Instant startTime;
    private Instant endTime;

    public Arena(
            String id,
            String name,
            ArenaType type,
            String worldName,
            int minPlayers,
            int maxPlayers,
            int countdownSeconds,
            int matchDurationSeconds,
            ArenaLocation lobbySpawn,
            ArenaLocation centerLocation,
            List<ArenaLocation> spawnPoints,
            boolean enabled
    ) {
        this.id = requireNonBlank(id, "ID arény");
        this.name = requireNonBlank(name, "Názov arény");
        this.type = Objects.requireNonNull(type, "Typ arény nesmie byť null");
        this.worldName = requireNonBlank(worldName, "Názov sveta");
        if (minPlayers < 1) {
            throw new IllegalArgumentException("minPlayers musí byť aspoň 1");
        }
        if (maxPlayers < minPlayers) {
            throw new IllegalArgumentException("maxPlayers nesmie byť menšie ako minPlayers");
        }
        if (countdownSeconds < 1) {
            throw new IllegalArgumentException("countdownSeconds musí byť aspoň 1");
        }
        if (matchDurationSeconds < 30) {
            throw new IllegalArgumentException("matchDurationSeconds musí byť aspoň 30 sekúnd");
        }
        this.minPlayers = minPlayers;
        this.maxPlayers = maxPlayers;
        this.countdownSeconds = countdownSeconds;
        this.matchDurationSeconds = matchDurationSeconds;
        this.lobbySpawn = Objects.requireNonNull(lobbySpawn, "lobbySpawn nesmie byť null");
        this.centerLocation = Objects.requireNonNull(centerLocation, "centerLocation nesmie byť null");
        this.spawnPoints = spawnPoints == null ? List.of() : List.copyOf(spawnPoints);
        this.state = enabled ? ArenaState.WAITING : ArenaState.DISABLED;
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " nesmie byť null ani prázdne");
        }
        return value.trim();
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public ArenaType getType() {
        return type;
    }

    public String getWorldName() {
        return worldName;
    }

    public int getMinPlayers() {
        return minPlayers;
    }

    public int getMaxPlayers() {
        return maxPlayers;
    }

    public int getCountdownSeconds() {
        return countdownSeconds;
    }

    public int getMatchDurationSeconds() {
        return matchDurationSeconds;
    }

    public ArenaLocation getLobbySpawn() {
        return lobbySpawn;
    }

    public ArenaLocation getCenterLocation() {
        return centerLocation;
    }

    public List<ArenaLocation> getSpawnPoints() {
        return spawnPoints;
    }

    public synchronized ArenaState getState() {
        return state;
    }

    public synchronized Instant getStartTime() {
        return startTime;
    }

    public synchronized Instant getEndTime() {
        return endTime;
    }

    public synchronized int getCurrentPlayerCount() {
        return players.size();
    }

    public synchronized int getAliveCount() {
        return alivePlayers.size();
    }

    public synchronized Set<UUID> getPlayers() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(players));
    }

    public synchronized Set<UUID> getAlivePlayers() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(alivePlayers));
    }

    public synchronized boolean containsPlayer(UUID playerId) {
        return playerId != null && players.contains(playerId);
    }

    public synchronized boolean isPlayerAlive(UUID playerId) {
        return playerId != null && alivePlayers.contains(playerId);
    }

    public synchronized boolean isFull() {
        return players.size() >= maxPlayers;
    }

    public synchronized boolean hasMinimumPlayers() {
        return players.size() >= minPlayers;
    }

    /**
     * Pokúsi sa bezpečne prejsť do nového stavu.
     *
     * @return true ak bol prechod platný a vykonaný, inak false
     */
    public synchronized boolean transitionTo(ArenaState target) {
        if (!state.canTransitionTo(target)) {
            return false;
        }
        this.state = target;
        if (target == ArenaState.INGAME) {
            this.startTime = Instant.now();
            this.endTime = null;
            this.alivePlayers.clear();
            this.alivePlayers.addAll(this.players);
        } else if (target == ArenaState.ENDING) {
            this.endTime = Instant.now();
        } else if (target == ArenaState.WAITING) {
            this.startTime = null;
            this.endTime = null;
            this.alivePlayers.clear();
        }
        return true;
    }

    /**
     * Vyžaduje platný prechod stavu a vyhodí výnimku pri nepovolenom prechode.
     */
    public synchronized void requireTransitionTo(ArenaState target) {
        if (!transitionTo(target)) {
            throw new IllegalStateException(
                    "Neplatný prechod stavu arény '" + id + "': " + state + " -> " + target
            );
        }
    }

    public synchronized boolean addPlayer(UUID playerId) {
        Objects.requireNonNull(playerId, "UUID hráča nesmie byť null");
        if (!state.isJoinable() || isFull() || players.contains(playerId)) {
            return false;
        }
        return players.add(playerId);
    }

    public synchronized boolean removePlayer(UUID playerId) {
        if (playerId == null) {
            return false;
        }
        alivePlayers.remove(playerId);
        return players.remove(playerId);
    }

    public synchronized boolean markEliminated(UUID playerId) {
        if (playerId == null) {
            return false;
        }
        return alivePlayers.remove(playerId);
    }

    public synchronized void clearSessionData() {
        players.clear();
        alivePlayers.clear();
        startTime = null;
        endTime = null;
    }
}
