package sk.arvyx.core.arena;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ArenaStateTransitionsTest {

    private Arena createSampleArena(boolean enabled) {
        return new Arena(
                "skyrush-a1",
                "SkyRush Test",
                ArenaType.SKYRUSH,
                "skyrush_arena_1",
                2,
                8,
                10,
                240,
                new ArenaLocation("skyrush_arena_1", 0.5, 80.0, 0.5),
                new ArenaLocation("skyrush_arena_1", 0.5, 65.0, 0.5),
                List.of(new ArenaLocation("skyrush_arena_1", 20.5, 65.0, 0.5)),
                enabled
        );
    }

    @Test
    void allowsValidFullLifecycleTransitions() {
        Arena arena = createSampleArena(false);
        assertEquals(ArenaState.DISABLED, arena.getState());

        assertTrue(arena.transitionTo(ArenaState.WAITING));
        assertEquals(ArenaState.WAITING, arena.getState());

        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        assertTrue(arena.addPlayer(p1));
        assertTrue(arena.addPlayer(p2));

        assertTrue(arena.transitionTo(ArenaState.STARTING));
        assertTrue(arena.transitionTo(ArenaState.INGAME));
        assertNotNull(arena.getStartTime());
        assertNull(arena.getEndTime());
        assertEquals(2, arena.getAliveCount());

        assertTrue(arena.transitionTo(ArenaState.ENDING));
        assertNotNull(arena.getEndTime());

        assertTrue(arena.transitionTo(ArenaState.RESETTING));
        assertTrue(arena.transitionTo(ArenaState.WAITING));
        assertNull(arena.getStartTime());
        assertNull(arena.getEndTime());
        assertEquals(0, arena.getAliveCount());
    }

    @Test
    void rejectsInvalidStateTransitions() {
        Arena arena = createSampleArena(true);
        assertEquals(ArenaState.WAITING, arena.getState());

        assertFalse(arena.transitionTo(ArenaState.INGAME));
        assertFalse(arena.transitionTo(ArenaState.ENDING));
        assertFalse(arena.transitionTo(ArenaState.WAITING));
        assertFalse(arena.transitionTo(null));
        assertEquals(ArenaState.WAITING, arena.getState());

        assertTrue(arena.transitionTo(ArenaState.STARTING));
        assertTrue(arena.transitionTo(ArenaState.INGAME));
        assertFalse(arena.transitionTo(ArenaState.WAITING));
        assertFalse(arena.transitionTo(ArenaState.STARTING));
        assertThrows(IllegalStateException.class, () -> arena.requireTransitionTo(ArenaState.WAITING));
    }

    @Test
    void allowsStartingRollbackToWaiting() {
        Arena arena = createSampleArena(true);
        assertTrue(arena.transitionTo(ArenaState.STARTING));
        assertTrue(arena.transitionTo(ArenaState.WAITING));
        assertEquals(ArenaState.WAITING, arena.getState());
    }
}
