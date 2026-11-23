package sk.arvyx.core.game.chaos;

import sk.arvyx.core.arena.Arena;
import sk.arvyx.core.arena.ArenaManager;
import sk.arvyx.core.arena.ArenaState;
import sk.arvyx.core.arena.ArenaType;
import sk.arvyx.core.util.Logger;

import java.util.Objects;
import java.util.Optional;

/**
 * Abstrakcia pre budúce bezpečné napojenie streamovacích udalostí na režim Chaos.
 * V ArvyxCore 1.0.0 neobsahuje nefunkčné externé sieťové konektory na Twitch/YouTube.
 */
public final class StreamEventBridge {

    private final ArenaManager arenaManager;
    private final ChaosEventManager eventManager;
    private final Logger logger;
    private volatile boolean streamingEnabled;

    public StreamEventBridge(ArenaManager arenaManager, ChaosEventManager eventManager, Logger logger) {
        this.arenaManager = Objects.requireNonNull(arenaManager, "ArenaManager nesmie byť null");
        this.eventManager = Objects.requireNonNull(eventManager, "ChaosEventManager nesmie byť null");
        this.logger = Objects.requireNonNull(logger, "Logger nesmie byť null");
    }

    public boolean isStreamingEnabled() {
        return streamingEnabled;
    }

    public void setStreamingEnabled(boolean streamingEnabled) {
        this.streamingEnabled = streamingEnabled;
    }

    public ChaosEventManager.TriggerResult handleTrigger(StreamTriggerRequest request) {
        Objects.requireNonNull(request, "Požiadavka nesmie byť null");
        if (!streamingEnabled) {
            logger.debug("Stream udalosť odmietnutá — streaming integrácia je vypnutá.");
            return ChaosEventManager.TriggerResult.EVENT_DISABLED;
        }
        Optional<Arena> arenaOpt = arenaManager.getArena(request.arenaId());
        if (arenaOpt.isEmpty()) {
            return ChaosEventManager.TriggerResult.ERROR;
        }
        Arena arena = arenaOpt.get();
        if (arena.getType() != ArenaType.CHAOS || arena.getState() != ArenaState.INGAME) {
            return ChaosEventManager.TriggerResult.ERROR;
        }
        return eventManager.triggerEvent(arena, request.eventId(), false);
    }
}
