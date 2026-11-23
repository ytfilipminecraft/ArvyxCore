package sk.arvyx.core.game;

import sk.arvyx.core.arena.Arena;
import sk.arvyx.core.arena.ArenaType;

import java.util.UUID;

/**
 * Rozhranie pre izolovaný herný modul (napr. SkyRush, Chaos).
 * Každý režim musí implementovať vlastnú logiku bez pevného previazania s inými režimami.
 */
public interface GameModule {

    ArenaType getType();

    String getName();

    void onEnable();

    void onDisable();

    void reloadConfig();

    void onArenaStart(Arena arena);

    void onArenaStop(Arena arena);

    void onArenaReset(Arena arena);

    void onPlayerEliminate(Arena arena, UUID victimId, UUID killerId);

    void onPlayerQuit(Arena arena, UUID playerId);

    boolean isGracePeriodActive(Arena arena);
}
