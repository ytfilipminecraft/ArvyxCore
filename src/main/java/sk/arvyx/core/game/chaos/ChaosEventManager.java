package sk.arvyx.core.game.chaos;

import sk.arvyx.core.arena.Arena;
import sk.arvyx.core.util.Logger;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Register a správca životného cyklu Chaos udalostí (aktivácia, cooldowny, deaktivácia, náhodný výber).
 */
public final class ChaosEventManager {

    private final Logger logger;
    private final Clock clock;
    private final Random random = new Random();

    private final Map<String, ChaosEvent> registry = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<String, Map<String, ActiveEventState>> activeEventsByArena = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Instant>> cooldownsByArena = new ConcurrentHashMap<>();

    private volatile int maxConcurrentEvents = 2;
    private volatile ChaosEventContext context;

    public ChaosEventManager(Logger logger) {
        this(logger, Clock.systemUTC());
    }

    public ChaosEventManager(Logger logger, Clock clock) {
        this.logger = Objects.requireNonNull(logger, "Logger nesmie byť null");
        this.clock = Objects.requireNonNull(clock, "Clock nesmie byť null");
        this.context = ChaosEventContext.headless(logger);
    }

    public void setContext(ChaosEventContext context) {
        this.context = Objects.requireNonNull(context, "Context nesmie byť null");
    }

    public void setMaxConcurrentEvents(int maxConcurrentEvents) {
        this.maxConcurrentEvents = Math.max(1, maxConcurrentEvents);
    }

    public int getMaxConcurrentEvents() {
        return maxConcurrentEvents;
    }

    public void registerEvent(ChaosEvent event) {
        Objects.requireNonNull(event, "ChaosEvent nesmie byť null");
        String id = normalizeId(event.getId());
        if (registry.containsKey(id)) {
            throw new IllegalArgumentException("Chaos udalosť '" + event.getId() + "' je už registrovaná.");
        }
        registry.put(id, event);
        logger.debug("Registrovaná Chaos udalosť: " + event.getId());
    }

    public Optional<ChaosEvent> getEvent(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(registry.get(normalizeId(eventId)));
    }

    public Collection<ChaosEvent> getRegisteredEvents() {
        synchronized (registry) {
            return List.copyOf(registry.values());
        }
    }

    public List<String> getRegisteredEventIds() {
        synchronized (registry) {
            return registry.values().stream().map(ChaosEvent::getId).toList();
        }
    }

    public TriggerResult triggerEvent(Arena arena, String eventId, boolean bypassCooldown) {
        Objects.requireNonNull(arena, "Aréna nesmie byť null");
        Optional<ChaosEvent> eventOpt = getEvent(eventId);
        if (eventOpt.isEmpty()) {
            return TriggerResult.EVENT_NOT_FOUND;
        }
        ChaosEvent event = eventOpt.get();
        if (!event.isEnabled()) {
            return TriggerResult.EVENT_DISABLED;
        }

        String arenaKey = normalizeId(arena.getId());
        String eventKey = normalizeId(event.getId());

        Map<String, ActiveEventState> arenaActive = activeEventsByArena.computeIfAbsent(
                arenaKey, ignored -> new ConcurrentHashMap<>()
        );

        if (arenaActive.containsKey(eventKey)) {
            return TriggerResult.ALREADY_ACTIVE;
        }
        if (arenaActive.size() >= maxConcurrentEvents) {
            return TriggerResult.MAX_CONCURRENT_REACHED;
        }

        if (!bypassCooldown && isOnCooldown(arena, event.getId())) {
            return TriggerResult.ON_COOLDOWN;
        }

        ActiveEventState activeState = new ActiveEventState(event, event.getDurationSeconds());
        arenaActive.put(eventKey, activeState);
        Instant cooldownUntil = clock.instant().plusSeconds(Math.max(0, event.getCooldownSeconds()));
        cooldownsByArena.computeIfAbsent(arenaKey, ignored -> new ConcurrentHashMap<>())
                .put(eventKey, cooldownUntil);

        try {
            event.onActivate(arena, context);
            logger.info("Chaos udalosť '" + event.getId() + "' aktivovaná v aréne '" + arena.getId() + "'.");
            return TriggerResult.ACTIVATED;
        } catch (Exception ex) {
            arenaActive.remove(eventKey);
            logger.error("Chyba pri aktivácii Chaos udalosti '" + event.getId() + "'", ex);
            return TriggerResult.ERROR;
        }
    }

    public Optional<ChaosEvent> triggerRandomEvent(Arena arena) {
        Objects.requireNonNull(arena, "Aréna nesmie byť null");
        List<ChaosEvent> candidates = new ArrayList<>();
        synchronized (registry) {
            for (ChaosEvent event : registry.values()) {
                if (event.isEnabled()
                        && !isActive(arena, event.getId())
                        && !isOnCooldown(arena, event.getId())) {
                    candidates.add(event);
                }
            }
        }
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        ChaosEvent chosen = candidates.get(random.nextInt(candidates.size()));
        TriggerResult result = triggerEvent(arena, chosen.getId(), false);
        return result == TriggerResult.ACTIVATED ? Optional.of(chosen) : Optional.empty();
    }

    /**
     * Posunie aktívne udalosti v aréne o 1 sekundu a deaktivuje expirované udalosti.
     *
     * @return zoznam udalostí, ktoré práve skončili
     */
    public List<ChaosEvent> tickArenaEvents(Arena arena) {
        if (arena == null) {
            return List.of();
        }
        String arenaKey = normalizeId(arena.getId());
        Map<String, ActiveEventState> arenaActive = activeEventsByArena.get(arenaKey);
        if (arenaActive == null || arenaActive.isEmpty()) {
            return List.of();
        }

        List<ChaosEvent> expired = new ArrayList<>();
        for (Map.Entry<String, ActiveEventState> entry : List.copyOf(arenaActive.entrySet())) {
            ActiveEventState state = entry.getValue();
            state.remainingSeconds--;
            if (state.remainingSeconds <= 0) {
                arenaActive.remove(entry.getKey());
                try {
                    state.event.onDeactivate(arena, context);
                } catch (Exception ex) {
                    logger.error("Chyba pri deaktivácii Chaos udalosti '" + state.event.getId() + "'", ex);
                }
                expired.add(state.event);
            } else {
                try {
                    state.event.onTick(arena, context, state.remainingSeconds);
                } catch (Exception ex) {
                    logger.error("Chyba počas tiku Chaos udalosti '" + state.event.getId() + "'", ex);
                }
            }
        }
        return expired;
    }

    public boolean deactivateEvent(Arena arena, String eventId) {
        if (arena == null || eventId == null) {
            return false;
        }
        Map<String, ActiveEventState> arenaActive = activeEventsByArena.get(normalizeId(arena.getId()));
        if (arenaActive == null) {
            return false;
        }
        ActiveEventState removed = arenaActive.remove(normalizeId(eventId));
        if (removed == null) {
            return false;
        }
        try {
            removed.event.onDeactivate(arena, context);
        } catch (Exception ex) {
            logger.error("Chyba pri manuálnej deaktivácii Chaos udalosti '" + removed.event.getId() + "'", ex);
        }
        return true;
    }

    public void deactivateAll(Arena arena) {
        if (arena == null) {
            return;
        }
        String arenaKey = normalizeId(arena.getId());
        Map<String, ActiveEventState> arenaActive = activeEventsByArena.remove(arenaKey);
        if (arenaActive != null) {
            for (ActiveEventState state : arenaActive.values()) {
                try {
                    state.event.onDeactivate(arena, context);
                } catch (Exception ex) {
                    logger.error("Chyba pri hromadnej deaktivácii Chaos udalosti '" + state.event.getId() + "'", ex);
                }
            }
        }
        cooldownsByArena.remove(arenaKey);
    }

    public boolean isActive(Arena arena, String eventId) {
        if (arena == null || eventId == null) {
            return false;
        }
        Map<String, ActiveEventState> arenaActive = activeEventsByArena.get(normalizeId(arena.getId()));
        return arenaActive != null && arenaActive.containsKey(normalizeId(eventId));
    }

    public boolean isOnCooldown(Arena arena, String eventId) {
        if (arena == null || eventId == null) {
            return false;
        }
        Map<String, Instant> arenaCooldowns = cooldownsByArena.get(normalizeId(arena.getId()));
        if (arenaCooldowns == null) {
            return false;
        }
        Instant until = arenaCooldowns.get(normalizeId(eventId));
        return until != null && clock.instant().isBefore(until);
    }

    public int getActiveEventCount(Arena arena) {
        if (arena == null) {
            return 0;
        }
        Map<String, ActiveEventState> arenaActive = activeEventsByArena.get(normalizeId(arena.getId()));
        return arenaActive == null ? 0 : arenaActive.size();
    }

    private static String normalizeId(String id) {
        return id.trim().toUpperCase(Locale.ROOT);
    }

    public enum TriggerResult {
        ACTIVATED,
        EVENT_NOT_FOUND,
        EVENT_DISABLED,
        ALREADY_ACTIVE,
        ON_COOLDOWN,
        MAX_CONCURRENT_REACHED,
        ERROR
    }

    private static final class ActiveEventState {
        private final ChaosEvent event;
        private int remainingSeconds;

        private ActiveEventState(ChaosEvent event, int remainingSeconds) {
            this.event = event;
            this.remainingSeconds = remainingSeconds;
        }
    }
}
