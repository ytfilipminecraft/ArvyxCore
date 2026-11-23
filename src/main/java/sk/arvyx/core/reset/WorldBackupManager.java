package sk.arvyx.core.reset;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.plugin.java.JavaPlugin;
import sk.arvyx.core.util.Logger;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Bezpečný správca záloh a obnovy svetov arén zo šablón.
 * Nikdy nemaže originálny svet bez existujúcej overenej zálohy a explicitného povolenia v konfigurácii.
 * Súborové operácie vykonáva mimo hlavného vlákna servera.
 */
public final class WorldBackupManager {

    private final Logger logger;
    private final Path backupsRoot;
    private final ExecutorService ioExecutor;

    private volatile boolean enabled;
    private volatile boolean allowWorldReplacement;

    public WorldBackupManager(Logger logger, Path backupsRoot, boolean enabled, boolean allowWorldReplacement) {
        this.logger = Objects.requireNonNull(logger, "Logger nesmie byť null");
        this.backupsRoot = Objects.requireNonNull(backupsRoot, "backupsRoot nesmie byť null");
        this.enabled = enabled;
        this.allowWorldReplacement = allowWorldReplacement;
        this.ioExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "arvyx-world-backup-io");
            t.setDaemon(true);
            return t;
        });
    }

    public void updateSettings(boolean enabled, boolean allowWorldReplacement) {
        this.enabled = enabled;
        this.allowWorldReplacement = allowWorldReplacement;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isAllowWorldReplacement() {
        return allowWorldReplacement;
    }

    public Path getBackupDirectory(String worldName) {
        Objects.requireNonNull(worldName, "worldName nesmie byť null");
        return backupsRoot.resolve(sanitizeWorldName(worldName));
    }

    public boolean hasValidBackup(String worldName) {
        if (worldName == null || worldName.isBlank()) {
            return false;
        }
        Path backupDir = getBackupDirectory(worldName);
        return Files.isDirectory(backupDir) && Files.exists(backupDir.resolve("level.dat"));
    }

    /**
     * Overí bezpečnostnú politiku pred nahradením priečinka sveta zálohou.
     */
    public boolean canSafelyReplaceWorld(String worldName) {
        return enabled && allowWorldReplacement && hasValidBackup(worldName);
    }

    /**
     * Asynchrónne vytvorí bezpečnú zálohu načítaného sveta.
     */
    public CompletableFuture<Boolean> createBackupAsync(World world) {
        Objects.requireNonNull(world, "world nesmie byť null");
        world.save();
        Path sourceDir = world.getWorldFolder().toPath();
        Path targetDir = getBackupDirectory(world.getName());

        return CompletableFuture.supplyAsync(() -> {
            try {
                Path stagingDir = backupsRoot.resolve(".staging-" + sanitizeWorldName(world.getName()));
                deleteDirectoryIfExists(stagingDir);
                copyWorldDirectory(sourceDir, stagingDir);
                deleteDirectoryIfExists(targetDir);
                Files.createDirectories(backupsRoot);
                Files.move(stagingDir, targetDir, StandardCopyOption.REPLACE_EXISTING);
                logger.info("Vytvorená záloha sveta '" + world.getName() + "' v " + targetDir);
                return true;
            } catch (Exception ex) {
                logger.error("Nepodarilo sa vytvoriť zálohu sveta '" + world.getName() + "'", ex);
                return false;
            }
        }, ioExecutor);
    }

    /**
     * Bezpečne obnoví svet z pripravenej zálohy bez blokovania hlavného Minecraft vlákna.
     */
    public void restoreWorldFromBackup(JavaPlugin plugin, String worldName, Consumer<Boolean> onCompleteMainThread) {
        if (!canSafelyReplaceWorld(worldName)) {
            logger.warn("Obnova sveta '" + worldName + "' zamietnutá: chýba overená záloha alebo povolenie.");
            completeOnMainThread(plugin, onCompleteMainThread, false);
            return;
        }

        World loadedWorld = Bukkit.getWorld(worldName);
        Path worldDir;
        if (loadedWorld != null) {
            if (!loadedWorld.getPlayers().isEmpty()) {
                logger.warn("Svet '" + worldName + "' stále obsahuje hráčov — obnova zrušená.");
                completeOnMainThread(plugin, onCompleteMainThread, false);
                return;
            }
            worldDir = loadedWorld.getWorldFolder().toPath();
            if (!Bukkit.unloadWorld(loadedWorld, false)) {
                logger.warn("Nepodarilo sa uvoľniť svet '" + worldName + "' pred obnovou.");
                completeOnMainThread(plugin, onCompleteMainThread, false);
                return;
            }
        } else {
            worldDir = Bukkit.getWorldContainer().toPath().resolve(sanitizeWorldName(worldName));
        }

        Path backupDir = getBackupDirectory(worldName);
        ioExecutor.execute(() -> {
            boolean restored = false;
            Path stagingDir = worldDir.getParent().resolve(".restore-" + sanitizeWorldName(worldName));
            Path previousDir = worldDir.getParent().resolve(".previous-" + sanitizeWorldName(worldName));
            boolean movedOriginal = false;
            try {
                deleteDirectoryIfExists(stagingDir);
                deleteDirectoryIfExists(previousDir);
                copyWorldDirectory(backupDir, stagingDir);
                if (!Files.isRegularFile(stagingDir.resolve("level.dat"))) {
                    throw new IOException("Pripravená záloha neobsahuje level.dat");
                }
                if (Files.exists(worldDir)) {
                    Files.move(worldDir, previousDir, StandardCopyOption.REPLACE_EXISTING);
                    movedOriginal = true;
                }
                try {
                    Files.move(stagingDir, worldDir, StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception moveFailure) {
                    if (movedOriginal && !Files.exists(worldDir)) {
                        Files.move(previousDir, worldDir, StandardCopyOption.REPLACE_EXISTING);
                        movedOriginal = false;
                    }
                    throw moveFailure;
                }
                restored = true;
            } catch (Exception ex) {
                logger.error("Chyba pri obnove sveta '" + worldName + "' zo zálohy", ex);
                try {
                    if (movedOriginal && !Files.exists(worldDir) && Files.exists(previousDir)) {
                        Files.move(previousDir, worldDir, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (Exception rollbackFailure) {
                    logger.error("Kritická chyba: nepodarilo sa vrátiť pôvodný svet '" + worldName + "'", rollbackFailure);
                }
            } finally {
                try {
                    deleteDirectoryIfExists(stagingDir);
                } catch (IOException cleanupFailure) {
                    logger.warn("Nepodarilo sa odstrániť dočasnú obnovovaciu zložku: " + cleanupFailure.getMessage());
                }
            }

            final boolean filesRestored = restored;
            if (plugin != null && plugin.isEnabled()) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    boolean success = false;
                    if (filesRestored) {
                        World recreated = Bukkit.createWorld(new WorldCreator(worldName));
                        success = recreated != null;
                        if (success) {
                            logger.info("Svet '" + worldName + "' bol obnovený zo zálohy a znova načítaný.");
                            try {
                                deleteDirectoryIfExists(previousDir);
                            } catch (IOException cleanupFailure) {
                                logger.warn("Pôvodná záloha sveta '" + worldName + "' zostala v .previous: "
                                        + cleanupFailure.getMessage());
                            }
                        } else {
                            logger.error("Súbory sveta '" + worldName + "' boli obnovené, ale Bukkit ho nedokázal načítať.");
                        }
                    }
                    if (onCompleteMainThread != null) {
                        onCompleteMainThread.accept(success);
                    }
                });
            }
        });
    }

    private void completeOnMainThread(JavaPlugin plugin, Consumer<Boolean> callback, boolean success) {
        if (callback == null) {
            return;
        }
        if (plugin == null || !plugin.isEnabled() || Bukkit.isPrimaryThread()) {
            callback.accept(success);
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> callback.accept(success));
    }

    public void shutdown() {
        ioExecutor.shutdown();
        try {
            if (!ioExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                ioExecutor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            ioExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static String sanitizeWorldName(String name) {
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    public static void copyWorldDirectory(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path rel = source.relativize(dir);
                Files.createDirectories(target.resolve(rel));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String fileName = file.getFileName().toString();
                if ("session.lock".equals(fileName) || "uid.dat".equals(fileName)) {
                    return FileVisitResult.CONTINUE;
                }
                Path rel = source.relativize(file);
                Files.copy(file, target.resolve(rel), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public static void deleteDirectoryIfExists(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
