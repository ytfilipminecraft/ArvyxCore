package sk.arvyx.core.arena;

import sk.arvyx.core.util.Logger;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Správca všetkých arén na serveri.
 */
public final class ArenaManager {

    private final Logger logger;
    private final Map<String, Arena> arenas = new LinkedHashMap<>();

    public ArenaManager(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "Logger nesmie byť null");
    }

    public synchronized void registerArena(Arena arena) {
        Objects.requireNonNull(arena, "Aréna nesmie byť null");
        String key = normalizeId(arena.getId());
        if (arenas.containsKey(key)) {
            throw new IllegalArgumentException("Aréna s ID '" + arena.getId() + "' je už registrovaná.");
        }
        arenas.put(key, arena);
        logger.info("Registrovaná aréna: " + arena.getId() + " (" + arena.getType() + ", stav=" + arena.getState() + ")");
    }

    public synchronized boolean unregisterArena(String arenaId) {
        if (arenaId == null || arenaId.isBlank()) {
            return false;
        }
        Arena removed = arenas.remove(normalizeId(arenaId));
        if (removed != null) {
            removed.clearSessionData();
            logger.info("Odregistrovaná aréna: " + removed.getId());
            return true;
        }
        return false;
    }

    public synchronized void clearArenas() {
        for (Arena arena : arenas.values()) {
            arena.clearSessionData();
        }
        arenas.clear();
    }

    public synchronized Optional<Arena> getArena(String arenaId) {
        if (arenaId == null || arenaId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(arenas.get(normalizeId(arenaId)));
    }

    public synchronized Collection<Arena> getAllArenas() {
        return Collections.unmodifiableList(List.copyOf(arenas.values()));
    }

    public synchronized List<Arena> getArenasByType(ArenaType type) {
        if (type == null) {
            return List.of();
        }
        return arenas.values().stream()
                .filter(arena -> arena.getType() == type)
                .toList();
    }

    public synchronized Optional<Arena> findArenaByPlayer(UUID playerId) {
        if (playerId == null) {
            return Optional.empty();
        }
        return arenas.values().stream()
                .filter(arena -> arena.containsPlayer(playerId))
                .findFirst();
    }

    public synchronized boolean transitionState(String arenaId, ArenaState targetState) {
        Optional<Arena> arenaOpt = getArena(arenaId);
        if (arenaOpt.isEmpty() || targetState == null) {
            return false;
        }
        Arena arena = arenaOpt.get();
        ArenaState previous = arena.getState();
        boolean changed = arena.transitionTo(targetState);
        if (changed) {
            logger.info("Stav arény '" + arena.getId() + "' zmenený: " + previous + " -> " + targetState);
        } else {
            logger.warn("Zamietnutý neplatný prechod arény '" + arena.getId() + "': " + previous + " -> " + targetState);
        }
        return changed;
    }

    public synchronized int getArenaCount() {
        return arenas.size();
    }

    private static String normalizeId(String id) {
        return id.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
