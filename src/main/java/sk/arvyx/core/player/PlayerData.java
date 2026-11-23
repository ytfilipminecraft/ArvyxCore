package sk.arvyx.core.player;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Údaje hráča identifikovaného primárne cez UUID.
 */
public final class PlayerData {

    private final UUID uuid;
    private String lastKnownName;
    private String currentArenaId;
    private boolean alive;
    private int kills;
    private int deaths;
    private int wins;
    private final Instant firstSeenAt;
    private Instant lastJoinedAt;

    public PlayerData(UUID uuid, String lastKnownName) {
        this.uuid = Objects.requireNonNull(uuid, "UUID hráča nesmie byť null");
        this.lastKnownName = (lastKnownName == null || lastKnownName.isBlank()) ? uuid.toString() : lastKnownName;
        this.firstSeenAt = Instant.now();
        this.lastJoinedAt = this.firstSeenAt;
    }

    public UUID getUuid() {
        return uuid;
    }

    public synchronized String getLastKnownName() {
        return lastKnownName;
    }

    public synchronized void setLastKnownName(String lastKnownName) {
        if (lastKnownName != null && !lastKnownName.isBlank()) {
            this.lastKnownName = lastKnownName;
        }
    }

    public synchronized Optional<String> getCurrentArenaId() {
        return Optional.ofNullable(currentArenaId);
    }

    public synchronized void setCurrentArenaId(String currentArenaId) {
        this.currentArenaId = currentArenaId;
    }

    public synchronized boolean isInArena() {
        return currentArenaId != null;
    }

    public synchronized boolean isAlive() {
        return alive;
    }

    public synchronized void setAlive(boolean alive) {
        this.alive = alive;
    }

    public synchronized int getKills() {
        return kills;
    }

    public synchronized void incrementKills() {
        this.kills++;
    }

    public synchronized int getDeaths() {
        return deaths;
    }

    public synchronized void incrementDeaths() {
        this.deaths++;
    }

    public synchronized int getWins() {
        return wins;
    }

    public synchronized void incrementWins() {
        this.wins++;
    }

    public Instant getFirstSeenAt() {
        return firstSeenAt;
    }

    public synchronized Instant getLastJoinedAt() {
        return lastJoinedAt;
    }

    public synchronized void markJoinedNow() {
        this.lastJoinedAt = Instant.now();
    }
}
