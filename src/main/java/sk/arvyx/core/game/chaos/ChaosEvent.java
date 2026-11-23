package sk.arvyx.core.game.chaos;

import sk.arvyx.core.arena.Arena;

/**
 * Rozhranie pre jednotlivé Chaos udalosti.
 * Každá udalosť je izolovaná vo vlastnej triede a podporuje aktiváciu aj čistú deaktiváciu.
 */
public interface ChaosEvent {

    String getId();

    String getName();

    String getDescription();

    int getDurationSeconds();

    int getCooldownSeconds();

    boolean isEnabled();

    void setEnabled(boolean enabled);

    void setName(String name);

    void setDescription(String description);

    void setDurationSeconds(int durationSeconds);

    void setCooldownSeconds(int cooldownSeconds);

    void onActivate(Arena arena, ChaosEventContext context);

    default void onTick(Arena arena, ChaosEventContext context, int remainingSeconds) {
        // Voliteľné periodické správanie počas trvania udalosti
    }

    void onDeactivate(Arena arena, ChaosEventContext context);
}
