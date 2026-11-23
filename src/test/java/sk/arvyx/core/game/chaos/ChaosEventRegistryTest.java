package sk.arvyx.core.game.chaos;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sk.arvyx.core.arena.Arena;
import sk.arvyx.core.arena.ArenaLocation;
import sk.arvyx.core.arena.ArenaManager;
import sk.arvyx.core.arena.ArenaState;
import sk.arvyx.core.arena.ArenaType;
import sk.arvyx.core.game.chaos.events.ChaosFogEvent;
import sk.arvyx.core.game.chaos.events.KnockbackStickEvent;
import sk.arvyx.core.game.chaos.events.LowGravityEvent;
import sk.arvyx.core.game.chaos.events.SpeedBoostEvent;
import sk.arvyx.core.game.chaos.events.TntRainEvent;
import sk.arvyx.core.util.Logger;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChaosEventRegistryTest {

    private ChaosEventManager eventManager;
    private Arena chaosArena;

    @BeforeEach
    void setUp() {
        Logger logger = Logger.createFallback("ChaosEventRegistryTest");
        eventManager = new ChaosEventManager(logger);
        ChaosModule.registerDefaultEvents(eventManager);

        chaosArena = new Arena(
                "chaos-a1",
                "Chaos Arena",
                ArenaType.CHAOS,
                "chaos_world",
                2,
                16,
                15,
                300,
                new ArenaLocation("chaos_world", 0, 80, 0),
                new ArenaLocation("chaos_world", 0, 65, 0),
                List.of(),
                true
        );
    }

    @Test
    void registersAllFiveRequiredChaosEvents() {
        assertEquals(5, eventManager.getRegisteredEvents().size());
        assertTrue(eventManager.getEvent(LowGravityEvent.ID).isPresent());
        assertTrue(eventManager.getEvent(KnockbackStickEvent.ID).isPresent());
        assertTrue(eventManager.getEvent(TntRainEvent.ID).isPresent());
        assertTrue(eventManager.getEvent(SpeedBoostEvent.ID).isPresent());
        assertTrue(eventManager.getEvent(ChaosFogEvent.ID).isPresent());

        assertThrows(IllegalArgumentException.class, () -> eventManager.registerEvent(new LowGravityEvent()));
    }

    @Test
    void enforcesActiveStateCooldownAndDeactivation() {
        ChaosEvent speed = eventManager.getEvent(SpeedBoostEvent.ID).orElseThrow();
        speed.setDurationSeconds(2);
        speed.setCooldownSeconds(30);

        assertEquals(ChaosEventManager.TriggerResult.ACTIVATED,
                eventManager.triggerEvent(chaosArena, SpeedBoostEvent.ID, false));
        assertTrue(eventManager.isActive(chaosArena, SpeedBoostEvent.ID));
        assertTrue(eventManager.isOnCooldown(chaosArena, SpeedBoostEvent.ID));

        assertEquals(ChaosEventManager.TriggerResult.ALREADY_ACTIVE,
                eventManager.triggerEvent(chaosArena, SpeedBoostEvent.ID, false));

        // 1. tick: ostáva 1s
        assertTrue(eventManager.tickArenaEvents(chaosArena).isEmpty());
        // 2. tick: expirácia a deaktivácia
        List<ChaosEvent> ended = eventManager.tickArenaEvents(chaosArena);
        assertEquals(1, ended.size());
        assertEquals(SpeedBoostEvent.ID, ended.getFirst().getId());
        assertFalse(eventManager.isActive(chaosArena, SpeedBoostEvent.ID));

        // Cooldown musí stále blokovať opätovné spustenie bez bypassu
        assertEquals(ChaosEventManager.TriggerResult.ON_COOLDOWN,
                eventManager.triggerEvent(chaosArena, SpeedBoostEvent.ID, false));
    }

    @Test
    void respectsMaxConcurrentEventsAndStreamBridgeGating() {
        eventManager.setMaxConcurrentEvents(2);
        assertEquals(ChaosEventManager.TriggerResult.ACTIVATED,
                eventManager.triggerEvent(chaosArena, LowGravityEvent.ID, false));
        assertEquals(ChaosEventManager.TriggerResult.ACTIVATED,
                eventManager.triggerEvent(chaosArena, KnockbackStickEvent.ID, false));
        assertEquals(ChaosEventManager.TriggerResult.MAX_CONCURRENT_REACHED,
                eventManager.triggerEvent(chaosArena, ChaosFogEvent.ID, false));

        eventManager.deactivateAll(chaosArena);
        assertEquals(0, eventManager.getActiveEventCount(chaosArena));

        ArenaManager arenaManager = new ArenaManager(Logger.createFallback("BridgeTest"));
        arenaManager.registerArena(chaosArena);
        chaosArena.transitionTo(ArenaState.STARTING);
        chaosArena.transitionTo(ArenaState.INGAME);

        StreamEventBridge bridge = new StreamEventBridge(arenaManager, eventManager, Logger.createFallback("BridgeTest"));
        StreamTriggerRequest req = new StreamTriggerRequest("twitch", "viewer1", "chaos-a1", TntRainEvent.ID, Instant.now());

        assertEquals(ChaosEventManager.TriggerResult.EVENT_DISABLED, bridge.handleTrigger(req));
        bridge.setStreamingEnabled(true);
        assertEquals(ChaosEventManager.TriggerResult.ACTIVATED, bridge.handleTrigger(req));
    }
}
