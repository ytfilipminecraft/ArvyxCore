package sk.arvyx.core.arena;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Reprezentácia jednej hernej arény v systéme ArvyxCore 1.1.1.
 * Všetky prechody stavov, hráči a časovo viazané rezervácie miest sú vláknovo bezpečné.
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
    private final Map<UUID, Instant> reservations = new LinkedHashMap<>();

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

    public synchronized int pruneExpiredReservations(Instant now) {
        Objects.requireNonNull(now, "now nesmie byť null");
        int before = reservations.size();
        reservations.entrySet().removeIf(entry -> !entry.getValue().isAfter(now));
        return before - reservations.size();
    }

    public synchronized int getReservedCount(Instant now) {
        pruneExpiredReservations(now);
        return reservations.size();
    }

    public synchronized int getEffectivePlayerCount(Instant now) {
        return players.size() + getReservedCount(now);
    }

    public synchronized boolean isFullIncludingReservations(Instant now) {
        return getEffectivePlayerCount(now) >= maxPlayers;
    }

    public synchronized boolean hasValidReservation(UUID playerId, Instant now) {
        if (playerId == null || now == null) {
            return false;
        }
        pruneExpiredReservations(now);
        return reservations.containsKey(playerId);
    }

    /**
     * Rezervuje miesto v aréne pre hráča presúvaného zo vzdialeného servera cez Velocity.
     */
    public synchronized boolean reserveSlot(UUID playerId, Instant expiresAt, Instant now) {
        Objects.requireNonNull(playerId, "playerId nesmie byť null");
        Objects.requireNonNull(expiresAt, "expiresAt nesmie byť null");
        Objects.requireNonNull(now, "now nesmie byť null");
        pruneExpiredReservations(now);

        if (!state.isJoinable() || players.contains(playerId) || !expiresAt.isAfter(now)) {
            return false;
        }
        if (reservations.containsKey(playerId)) {
            reservations.put(playerId, expiresAt);
            return true;
        }
        if (isFullIncludingReservations(now)) {
            return false;
        }
        reservations.put(playerId, expiresAt);
        return true;
    }

    public synchronized boolean consumeReservation(UUID playerId, Instant now) {
        if (playerId == null || now == null) {
            return false;
        }
        pruneExpiredReservations(now);
        return reservations.remove(playerId) != null;
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
            this.reservations.clear();
            this.alivePlayers.clear();
            this.alivePlayers.addAll(this.players);
        } else if (target == ArenaState.ENDING) {
            this.endTime = Instant.now();
            this.reservations.clear();
        } else if (target == ArenaState.WAITING) {
            this.startTime = null;
            this.endTime = null;
            this.reservations.clear();
            this.alivePlayers.clear();
        } else if (target == ArenaState.DISABLED || target == ArenaState.RESETTING) {
            this.reservations.clear();
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
        Instant now = Instant.now();
        boolean hadReservation = consumeReservation(playerId, now);
        if (!state.isJoinable() || players.contains(playerId)) {
            return false;
        }
        if (hadReservation ? isFull() : isFullIncludingReservations(now)) {
            return false;
        }
        return players.add(playerId);
    }

    public synchronized boolean removePlayer(UUID playerId) {
        if (playerId == null) {
            return false;
        }
        reservations.remove(playerId);
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
        reservations.clear();
        startTime = null;
        endTime = null;
    }
}
