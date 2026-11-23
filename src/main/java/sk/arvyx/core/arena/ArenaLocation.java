package sk.arvyx.core.arena;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.Objects;
import java.util.Optional;

/**
 * Konfigurovateľná pozícia arény nezávislá od toho, či je svet momentálne načítaný.
 */
public record ArenaLocation(
        String worldName,
        double x,
        double y,
        double z,
        float yaw,
        float pitch
) {

    public ArenaLocation {
        Objects.requireNonNull(worldName, "Názov sveta nesmie byť null");
        if (worldName.isBlank()) {
            throw new IllegalArgumentException("Názov sveta nesmie byť prázdny");
        }
    }

    public ArenaLocation(String worldName, double x, double y, double z) {
        this(worldName, x, y, z, 0.0f, 0.0f);
    }

    public Optional<Location> toBukkitLocation() {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return Optional.empty();
        }
        return Optional.of(new Location(world, x, y, z, yaw, pitch));
    }

    public Location toBukkitLocation(World world) {
        Objects.requireNonNull(world, "Svet nesmie byť null");
        return new Location(world, x, y, z, yaw, pitch);
    }
}
