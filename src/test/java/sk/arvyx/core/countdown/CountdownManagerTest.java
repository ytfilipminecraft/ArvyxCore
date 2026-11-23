package sk.arvyx.core.countdown;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sk.arvyx.core.arena.Arena;
import sk.arvyx.core.arena.ArenaLocation;
import sk.arvyx.core.arena.ArenaState;
import sk.arvyx.core.arena.ArenaType;
import sk.arvyx.core.util.Logger;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class CountdownManagerTest {

    private CountdownManager countdownManager;
    private Arena arena;

    @BeforeEach
    void setUp() {
        countdownManager = new CountdownManager(Logger.createFallback("CountdownManagerTest"));
        arena = new Arena(
                "skyrush-a1",
                "SkyRush Test",
                ArenaType.SKYRUSH,
                "skyrush_world",
                2,
                8,
                3,
                240,
                new ArenaLocation("skyrush_world", 0, 80, 0),
                new ArenaLocation("skyrush_world", 0, 65, 0),
                List.of(),
                true
        );
    }

    @Test
    void preventsStartWithoutMinimumPlayersAndPreventsDuplicates() {
        arena.addPlayer(UUID.randomUUID());
        assertFalse(countdownManager.startCountdown(arena, a -> {}));
        assertEquals(ArenaState.WAITING, arena.getState());

        arena.addPlayer(UUID.randomUUID());
        assertTrue(countdownManager.startCountdown(arena, a -> {}));
        assertEquals(ArenaState.STARTING, arena.getState());
        assertTrue(countdownManager.isCountingDown("skyrush-a1"));

        // Duplicitný štart musí byť zamietnutý
        assertFalse(countdownManager.startCountdown(arena, a -> {}));
    }

    @Test
    void completesCountdownAndTransitionsToIngame() {
        arena.addPlayer(UUID.randomUUID());
        arena.addPlayer(UUID.randomUUID());

        AtomicBoolean startedCallback = new AtomicBoolean(false);
        assertTrue(countdownManager.startCountdown(arena, a -> startedCallback.set(true)));

        assertEquals(CountdownManager.TickOutcome.TICKED, countdownManager.tickSession("skyrush-a1"));
        assertEquals(2, countdownManager.getRemainingSeconds("skyrush-a1"));
        assertEquals(CountdownManager.TickOutcome.TICKED, countdownManager.tickSession("skyrush-a1"));
        assertEquals(CountdownManager.TickOutcome.TICKED, countdownManager.tickSession("skyrush-a1"));
        assertEquals(CountdownManager.TickOutcome.STARTED, countdownManager.tickSession("skyrush-a1"));

        assertTrue(startedCallback.get());
        assertEquals(ArenaState.INGAME, arena.getState());
        assertFalse(countdownManager.isCountingDown("skyrush-a1"));
    }

    @Test
    void cancelsCountdownWhenPlayersDropBelowMinimum() {
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        arena.addPlayer(p1);
        arena.addPlayer(p2);

        AtomicBoolean startedCallback = new AtomicBoolean(false);
        assertTrue(countdownManager.startCountdown(arena, a -> startedCallback.set(true)));
        assertEquals(CountdownManager.TickOutcome.TICKED, countdownManager.tickSession("skyrush-a1"));

        assertTrue(arena.removePlayer(p2));
        assertEquals(CountdownManager.TickOutcome.CANCELLED_LOW_PLAYERS, countdownManager.tickSession("skyrush-a1"));

        assertFalse(startedCallback.get());
        assertEquals(ArenaState.WAITING, arena.getState());
        assertFalse(countdownManager.isCountingDown("skyrush-a1"));
    }
}
