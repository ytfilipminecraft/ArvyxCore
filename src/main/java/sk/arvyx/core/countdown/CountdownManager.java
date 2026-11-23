package sk.arvyx.core.countdown;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import sk.arvyx.core.arena.Arena;
import sk.arvyx.core.arena.ArenaState;
import sk.arvyx.core.util.Logger;
import sk.arvyx.core.util.MessageFormatter;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Správca odpočítavania pred štartom zápasu.
 * Zabraňuje duplicitným úlohám, upozorňuje hráčov a ruší odpočítavanie pri poklese hráčov pod minimum.
 */
public final class CountdownManager {

    private final Logger logger;
    private final Map<String, CountdownSession> sessions = new ConcurrentHashMap<>();

    private JavaPlugin plugin;
    private MessageFormatter messageFormatter;
    private Set<Integer> notifyIntervals = new HashSet<>(List.of(30, 15, 10, 5, 4, 3, 2, 1));
    private String tickMessage = "<prefix> <#E2E8F0>Hra začína za <#C084FC><seconds>s<#E2E8F0>.";
    private String startedMessage = "<prefix> <gradient:#8A2BE2:#4169E1><bold>ZÁPAS SA ZAČAL!</bold></gradient> <#E2E8F0>Veľa šťastia.";
    private String cancelledLowPlayersMessage = "<prefix> <#F59E0B>Odpočítavanie bolo zrušené pre nedostatok hráčov.";

    public CountdownManager(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "Logger nesmie byť null");
    }

    public void attachRuntime(
            JavaPlugin plugin,
            MessageFormatter messageFormatter,
            Collection<Integer> notifyIntervals,
            String tickMessage,
            String startedMessage,
            String cancelledLowPlayersMessage
    ) {
        this.plugin = plugin;
        this.messageFormatter = messageFormatter;
        updateSettings(notifyIntervals, tickMessage, startedMessage, cancelledLowPlayersMessage);
    }

    public void updateSettings(
            Collection<Integer> notifyIntervals,
            String tickMessage,
            String startedMessage,
            String cancelledLowPlayersMessage
    ) {
        if (notifyIntervals != null && !notifyIntervals.isEmpty()) {
            this.notifyIntervals = new HashSet<>(notifyIntervals);
        }
        if (tickMessage != null && !tickMessage.isBlank()) {
            this.tickMessage = tickMessage;
        }
        if (startedMessage != null && !startedMessage.isBlank()) {
            this.startedMessage = startedMessage;
        }
        if (cancelledLowPlayersMessage != null && !cancelledLowPlayersMessage.isBlank()) {
            this.cancelledLowPlayersMessage = cancelledLowPlayersMessage;
        }
    }

    /**
     * Spustí odpočítavanie pre arénu na Paper serveri. Zabraňuje duplicitným úlohám.
     */
    public boolean startCountdown(Arena arena, Consumer<Arena> onComplete) {
        Objects.requireNonNull(arena, "Aréna nesmie byť null");
        Objects.requireNonNull(onComplete, "onComplete callback nesmie byť null");

        String key = normalizeKey(arena.getId());
        if (sessions.containsKey(key)) {
            logger.debug("Odpočítavanie pre arénu '" + arena.getId() + "' už beží — duplicitný štart zamietnutý.");
            return false;
        }
        if (!arena.hasMinimumPlayers()) {
            return false;
        }
        if (arena.getState() == ArenaState.WAITING && !arena.transitionTo(ArenaState.STARTING)) {
            return false;
        }
        if (arena.getState() != ArenaState.STARTING) {
            return false;
        }

        CountdownSession session = new CountdownSession(arena, arena.getCountdownSeconds(), onComplete);
        CountdownSession previous = sessions.putIfAbsent(key, session);
        if (previous != null) {
            return false;
        }

        if (plugin != null && plugin.isEnabled()) {
            BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                TickOutcome outcome = tickSession(arena.getId());
                if (outcome == TickOutcome.CANCELLED_LOW_PLAYERS) {
                    notifyArena(arena, cancelledLowPlayersMessage, Map.of(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f);
                } else if (outcome == TickOutcome.STARTED) {
                    notifyArena(arena, startedMessage, Map.of(), Sound.ENTITY_ENDER_DRAGON_GROWL, 1.0f);
                }
            }, 0L, 20L);
            session.setTask(task);
        }

        logger.info("Spustené odpočítavanie (" + arena.getCountdownSeconds() + "s) pre arénu " + arena.getId());
        return true;
    }

    /**
     * Čistá jednotkovo testovateľná logika jedného kroku odpočítavania (1 sekunda).
     */
    public TickOutcome tickSession(String arenaId) {
        if (arenaId == null) {
            return TickOutcome.NO_SESSION;
        }
        CountdownSession session = sessions.get(normalizeKey(arenaId));
        if (session == null) {
            return TickOutcome.NO_SESSION;
        }
        Arena arena = session.arena;

        if (arena.getState() != ArenaState.STARTING) {
            cancelCountdown(arenaId, false);
            return TickOutcome.CANCELLED_INVALID_STATE;
        }

        if (!arena.hasMinimumPlayers()) {
            cancelCountdown(arenaId, true);
            logger.info("Odpočítavanie arény '" + arena.getId() + "' zrušené pre nedostatok hráčov ("
                    + arena.getCurrentPlayerCount() + "/" + arena.getMinPlayers() + ").");
            return TickOutcome.CANCELLED_LOW_PLAYERS;
        }

        int secondsLeft = session.remainingSeconds;
        if (secondsLeft <= 0) {
            sessions.remove(normalizeKey(arenaId));
            session.cancelTask();
            if (arena.transitionTo(ArenaState.INGAME)) {
                session.onComplete.accept(arena);
                return TickOutcome.STARTED;
            }
            return TickOutcome.CANCELLED_INVALID_STATE;
        }

        if (notifyIntervals.contains(secondsLeft) || secondsLeft == arena.getCountdownSeconds()) {
            if (plugin != null && plugin.isEnabled()) {
                float pitch = secondsLeft <= 3 ? 1.5f : 1.1f;
                notifyArena(
                        arena,
                        tickMessage,
                        Map.of("seconds", String.valueOf(secondsLeft)),
                        Sound.BLOCK_NOTE_BLOCK_PLING,
                        pitch
                );
            }
        }

        session.remainingSeconds--;
        return TickOutcome.TICKED;
    }

    public boolean isCountingDown(String arenaId) {
        return arenaId != null && sessions.containsKey(normalizeKey(arenaId));
    }

    public int getRemainingSeconds(String arenaId) {
        if (arenaId == null) {
            return -1;
        }
        CountdownSession session = sessions.get(normalizeKey(arenaId));
        return session == null ? -1 : session.remainingSeconds;
    }

    public boolean cancelCountdown(String arenaId, boolean revertToWaiting) {
        if (arenaId == null) {
            return false;
        }
        CountdownSession removed = sessions.remove(normalizeKey(arenaId));
        if (removed == null) {
            return false;
        }
        removed.cancelTask();
        if (revertToWaiting && removed.arena.getState() == ArenaState.STARTING) {
            removed.arena.transitionTo(ArenaState.WAITING);
        }
        return true;
    }

    public void cancelAll() {
        for (CountdownSession session : sessions.values()) {
            session.cancelTask();
        }
        sessions.clear();
    }

    private void notifyArena(Arena arena, String template, Map<String, String> placeholders, Sound sound, float pitch) {
        if (messageFormatter == null) {
            return;
        }
        for (UUID uuid : arena.getPlayers()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                messageFormatter.send(player, template, placeholders);
                player.playSound(player.getLocation(), sound, 1.0f, pitch);
            }
        }
    }

    private static String normalizeKey(String id) {
        return id.trim().toLowerCase(Locale.ROOT);
    }

    public enum TickOutcome {
        NO_SESSION,
        TICKED,
        STARTED,
        CANCELLED_LOW_PLAYERS,
        CANCELLED_INVALID_STATE
    }

    private static final class CountdownSession {
        private final Arena arena;
        private int remainingSeconds;
        private final Consumer<Arena> onComplete;
        private BukkitTask task;

        private CountdownSession(Arena arena, int initialSeconds, Consumer<Arena> onComplete) {
            this.arena = arena;
            this.remainingSeconds = initialSeconds;
            this.onComplete = onComplete;
        }

        private void setTask(BukkitTask task) {
            this.task = task;
        }

        private void cancelTask() {
            if (task != null) {
                task.cancel();
                task = null;
            }
        }
    }
}
