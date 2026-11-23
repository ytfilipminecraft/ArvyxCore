package sk.arvyx.core.arena;

import java.util.Locale;
import java.util.Optional;

/**
 * Podporované herné režimy arén v rámci siete ARVYX NETWORK.
 */
public enum ArenaType {

    SKYRUSH("SkyRush"),
    CHAOS("Chaos");

    private final String displayName;

    ArenaType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static Optional<ArenaType> fromString(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(ArenaType.valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }
}
