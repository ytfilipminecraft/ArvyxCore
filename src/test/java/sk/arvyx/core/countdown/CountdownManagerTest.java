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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
    void allowsOnlySingleSessionUnderConcurrentStartAttempts() throws InterruptedException {
        arena.addPlayer(UUID.randomUUID());
        arena.addPlayer(UUID.randomUUID());

        int threads = 8;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger successes = new AtomicInteger();

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                pool.execute(() -> {
                    ready.countDown();
                    try {
                        go.await();
                        if (countdownManager.startCountdown(arena, a -> {})) {
                            successes.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(ready.await(3, TimeUnit.SECONDS));
            go.countDown();
            assertTrue(done.await(3, TimeUnit.SECONDS));
        }

        assertEquals(1, successes.get());
        assertEquals(ArenaState.STARTING, arena.getState());
        assertTrue(countdownManager.isCountingDown("skyrush-a1"));
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
