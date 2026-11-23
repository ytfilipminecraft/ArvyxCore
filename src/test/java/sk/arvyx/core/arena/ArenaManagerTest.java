package sk.arvyx.core.arena;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sk.arvyx.core.util.Logger;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ArenaManagerTest {

    private ArenaManager arenaManager;

    @BeforeEach
    void setUp() {
        arenaManager = new ArenaManager(Logger.createFallback("ArenaManagerTest"));
    }

    private Arena buildArena(String id, ArenaType type, boolean enabled) {
        return new Arena(
                id,
                "Arena " + id,
                type,
                "world_" + id,
                2,
                8,
                10,
                240,
                new ArenaLocation("world_" + id, 0, 80, 0),
                new ArenaLocation("world_" + id, 0, 65, 0),
                List.of(),
                enabled
        );
    }

    @Test
    void registersAndQueriesArenasCaseInsensitively() {
        Arena skyRush = buildArena("skyrush-1", ArenaType.SKYRUSH, true);
        Arena chaos = buildArena("chaos-1", ArenaType.CHAOS, true);

        arenaManager.registerArena(skyRush);
        arenaManager.registerArena(chaos);

        assertEquals(2, arenaManager.getArenaCount());
        assertTrue(arenaManager.getArena("SKYRUSH-1").isPresent());
        assertEquals(1, arenaManager.getArenasByType(ArenaType.SKYRUSH).size());
        assertEquals(1, arenaManager.getArenasByType(ArenaType.CHAOS).size());
    }

    @Test
    void rejectsDuplicateArenaRegistration() {
        arenaManager.registerArena(buildArena("skyrush-1", ArenaType.SKYRUSH, true));
        assertThrows(IllegalArgumentException.class,
                () -> arenaManager.registerArena(buildArena("SKYRUSH-1", ArenaType.SKYRUSH, true)));
    }

    @Test
    void transitionsStateAndFindsPlayerArena() {
        Arena arena = buildArena("skyrush-1", ArenaType.SKYRUSH, true);
        arenaManager.registerArena(arena);

        UUID player = UUID.randomUUID();
        assertTrue(arena.addPlayer(player));
        assertEquals("skyrush-1", arenaManager.findArenaByPlayer(player).orElseThrow().getId());

        assertTrue(arenaManager.transitionState("skyrush-1", ArenaState.STARTING));
        assertEquals(ArenaState.STARTING, arena.getState());
        assertFalse(arenaManager.transitionState("skyrush-1", ArenaState.RESETTING));
        assertEquals(ArenaState.STARTING, arena.getState());
    }
}
