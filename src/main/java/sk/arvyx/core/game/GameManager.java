package sk.arvyx.core.game;

import sk.arvyx.core.arena.Arena;
import sk.arvyx.core.arena.ArenaType;
import sk.arvyx.core.util.Logger;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Centrálny register a dispečer herných modulov (SkyRush, Chaos).
 */
public final class GameManager {

    private final Logger logger;
    private final Map<ArenaType, GameModule> modules = new EnumMap<>(ArenaType.class);

    public GameManager(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "Logger nesmie byť null");
    }

    public void registerModule(GameModule module) {
        Objects.requireNonNull(module, "GameModule nesmie byť null");
        ArenaType type = Objects.requireNonNull(module.getType(), "ArenaType modulu nesmie byť null");
        if (modules.containsKey(type)) {
            throw new IllegalStateException("Herný modul pre typ " + type + " je už zaregistrovaný.");
        }
        modules.put(type, module);
        logger.info("Zaregistrovaný herný modul: " + module.getName() + " (" + type + ")");
    }

    public void enableAll() {
        for (GameModule module : modules.values()) {
            try {
                module.onEnable();
            } catch (Exception ex) {
                logger.error("Nepodarilo sa aktivovať herný modul " + module.getName(), ex);
            }
        }
    }

    public void disableAll() {
        for (GameModule module : modules.values()) {
            try {
                module.onDisable();
            } catch (Exception ex) {
                logger.error("Chyba pri vypínaní herného modulu " + module.getName(), ex);
            }
        }
    }

    public void reloadAll() {
        for (GameModule module : modules.values()) {
            try {
                module.reloadConfig();
            } catch (Exception ex) {
                logger.error("Chyba pri reloadovaní konfigurácie modulu " + module.getName(), ex);
            }
        }
    }

    public Optional<GameModule> getModule(ArenaType type) {
        return type == null ? Optional.empty() : Optional.ofNullable(modules.get(type));
    }

    public Collection<GameModule> getModules() {
        return Collections.unmodifiableCollection(modules.values());
    }

    public void startGame(Arena arena) {
        Objects.requireNonNull(arena, "Aréna nesmie byť null");
        getModule(arena.getType()).ifPresentOrElse(
                module -> module.onArenaStart(arena),
                () -> logger.warn("Pre arénu " + arena.getId() + " nie je registrovaný modul typu " + arena.getType())
        );
    }

    public void stopGame(Arena arena) {
        Objects.requireNonNull(arena, "Aréna nesmie byť null");
        getModule(arena.getType()).ifPresent(module -> module.onArenaStop(arena));
    }

    public void resetGame(Arena arena) {
        Objects.requireNonNull(arena, "Aréna nesmie byť null");
        getModule(arena.getType()).ifPresent(module -> module.onArenaReset(arena));
    }

    public void handlePlayerElimination(Arena arena, UUID victimId, UUID killerId) {
        if (arena == null || victimId == null) {
            return;
        }
        getModule(arena.getType()).ifPresent(module -> module.onPlayerEliminate(arena, victimId, killerId));
    }

    public void handlePlayerQuit(Arena arena, UUID playerId) {
        if (arena == null || playerId == null) {
            return;
        }
        getModule(arena.getType()).ifPresent(module -> module.onPlayerQuit(arena, playerId));
    }

    public boolean isGracePeriodActive(Arena arena) {
        if (arena == null) {
            return false;
        }
        return getModule(arena.getType())
                .map(module -> module.isGracePeriodActive(arena))
                .orElse(false);
    }
}
