package sk.arvyx.core.network;

import sk.arvyx.core.arena.Arena;
import sk.arvyx.core.arena.ArenaState;
import sk.arvyx.core.arena.ArenaType;

import java.time.Instant;
import java.util.Objects;

/**
 * Snímka aktuálneho stavu arény určená pre sieťové zdieľanie medzi servermi.
 */
public record ArenaStatusSnapshot(
        String arenaId,
        String serverId,
        ArenaType type,
        ArenaState state,
        int currentPlayers,
        int maxPlayers,
        Instant updatedAt
) {
    public ArenaStatusSnapshot {
        Objects.requireNonNull(arenaId, "arenaId nesmie byť null");
        Objects.requireNonNull(serverId, "serverId nesmie byť null");
        Objects.requireNonNull(type, "type nesmie byť null");
        Objects.requireNonNull(state, "state nesmie byť null");
        Objects.requireNonNull(updatedAt, "updatedAt nesmie byť null");
    }

    public static ArenaStatusSnapshot fromArena(String serverId, Arena arena) {
        return new ArenaStatusSnapshot(
                arena.getId(),
                serverId,
                arena.getType(),
                arena.getState(),
                arena.getCurrentPlayerCount(),
                arena.getMaxPlayers(),
                Instant.now()
        );
    }
}
