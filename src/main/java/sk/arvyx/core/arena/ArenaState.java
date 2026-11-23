package sk.arvyx.core.arena;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Životný cyklus arény s presne definovanými povolenými prechodmi medzi stavmi.
 */
public enum ArenaState {

    DISABLED("Vypnutá", "<#64748B>", false),
    WAITING("Čaká na hráčov", "<#60A5FA>", true),
    STARTING("Spúšťa sa", "<#C084FC>", true),
    INGAME("Prebieha hra", "<#22C55E>", false),
    ENDING("Ukončuje sa", "<#F59E0B>", false),
    RESETTING("Resetuje sa", "<#8A2BE2>", false);

    private static final Map<ArenaState, Set<ArenaState>> ALLOWED_TRANSITIONS = new EnumMap<>(ArenaState.class);

    static {
        ALLOWED_TRANSITIONS.put(DISABLED, EnumSet.of(WAITING));
        ALLOWED_TRANSITIONS.put(WAITING, EnumSet.of(STARTING, DISABLED, RESETTING));
        ALLOWED_TRANSITIONS.put(STARTING, EnumSet.of(WAITING, INGAME, ENDING, DISABLED));
        ALLOWED_TRANSITIONS.put(INGAME, EnumSet.of(ENDING, RESETTING));
        ALLOWED_TRANSITIONS.put(ENDING, EnumSet.of(RESETTING, DISABLED));
        ALLOWED_TRANSITIONS.put(RESETTING, EnumSet.of(WAITING, DISABLED));
    }

    private final String displayName;
    private final String colorTag;
    private final boolean joinable;

    ArenaState(String displayName, String colorTag, boolean joinable) {
        this.displayName = displayName;
        this.colorTag = colorTag;
        this.joinable = joinable;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getColorTag() {
        return colorTag;
    }

    public boolean isJoinable() {
        return joinable;
    }

    /**
     * Overí, či je prechod do cieľového stavu platný podľa pravidiel životného cyklu.
     */
    public boolean canTransitionTo(ArenaState target) {
        if (target == null || target == this) {
            return false;
        }
        Set<ArenaState> allowed = ALLOWED_TRANSITIONS.get(this);
        return allowed != null && allowed.contains(target);
    }

    /**
     * Vráti nemennú množinu všetkých stavov, do ktorých možno z aktuálneho stavu prejsť.
     */
    public Set<ArenaState> getAllowedTransitions() {
        Set<ArenaState> allowed = ALLOWED_TRANSITIONS.get(this);
        return allowed == null ? Set.of() : Set.copyOf(allowed);
    }
}
