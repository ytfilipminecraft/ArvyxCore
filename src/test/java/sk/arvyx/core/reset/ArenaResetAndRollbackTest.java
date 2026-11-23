package sk.arvyx.core.reset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sk.arvyx.core.config.ConfigManager;
import sk.arvyx.core.util.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ArenaResetAndRollbackTest {

    @Test
    void tracksFirstTouchBlockStatesAndRestoresInReverseOrder() {
        ArenaBlockTracker tracker = new ArenaBlockTracker(Logger.createFallback("BlockTrackerTest"));

        tracker.recordOriginalState("skyrush-1a", "skyrush_arena_1", 10, 64, 10, "minecraft:air");
        // Opakovaná úprava tej istej súradnice nesmie prepísať pôvodný stav
        tracker.recordOriginalState("skyrush-1a", "skyrush_arena_1", 10, 64, 10, "minecraft:oak_planks");
        tracker.recordOriginalState("skyrush-1a", "skyrush_arena_1", 11, 64, 10, "minecraft:stone");

        assertEquals(2, tracker.getTrackedBlockCount("skyrush-1a"));

        List<ArenaBlockTracker.BlockSnapshot> restoreOrder = tracker.drainSnapshotsInRestoreOrder("skyrush-1a");
        assertEquals(2, restoreOrder.size());
        assertEquals(11, restoreOrder.get(0).coord().x());
        assertEquals("minecraft:stone", restoreOrder.get(0).originalBlockData());
        assertEquals(10, restoreOrder.get(1).coord().x());
        assertEquals("minecraft:air", restoreOrder.get(1).originalBlockData());
        assertEquals(0, tracker.getTrackedBlockCount("skyrush-1a"));
    }

    @Test
    void blocksWorldReplacementWithoutVerifiedBackupAndExplicitPermission(@TempDir Path tempDir) throws IOException {
        Logger logger = Logger.createFallback("BackupTest");
        Path backupsRoot = tempDir.resolve("backups");
        WorldBackupManager backupManager = new WorldBackupManager(logger, backupsRoot, true, false);

        // Chýba záloha aj allowWorldReplacement=false
        assertFalse(backupManager.hasValidBackup("skyrush_arena_1"));
        assertFalse(backupManager.canSafelyReplaceWorld("skyrush_arena_1"));

        // Vytvorenie validnej zálohy (obsahuje level.dat)
        Path worldBackup = backupsRoot.resolve("skyrush_arena_1");
        Files.createDirectories(worldBackup);
        Files.writeString(worldBackup.resolve("level.dat"), "dummy-nbt");
        assertTrue(backupManager.hasValidBackup("skyrush_arena_1"));

        // Stále zablokované, pretože allowWorldReplacement je false
        assertFalse(backupManager.canSafelyReplaceWorld("skyrush_arena_1"));

        // Až po explicitnom povolení a existujúcej zálohe je obnova povolená
        backupManager.updateSettings(true, true);
        assertTrue(backupManager.canSafelyReplaceWorld("skyrush_arena_1"));

        // Kopírovanie adresára sveta musí ignorovať session.lock a uid.dat
        Path srcWorld = tempDir.resolve("src_world");
        Path dstWorld = tempDir.resolve("dst_world");
        Files.createDirectories(srcWorld.resolve("region"));
        Files.writeString(srcWorld.resolve("level.dat"), "world-data");
        Files.writeString(srcWorld.resolve("session.lock"), "lock");
        Files.writeString(srcWorld.resolve("uid.dat"), "uid");
        Files.writeString(srcWorld.resolve("region/r.0.0.mca"), "region");

        WorldBackupManager.copyWorldDirectory(srcWorld, dstWorld);
        assertTrue(Files.exists(dstWorld.resolve("level.dat")));
        assertTrue(Files.exists(dstWorld.resolve("region/r.0.0.mca")));
        assertFalse(Files.exists(dstWorld.resolve("session.lock")));
        assertFalse(Files.exists(dstWorld.resolve("uid.dat")));
    }

    @Test
    void enforcesServerScopedArenaLoading() {
        assertTrue(ConfigManager.shouldLoadArenaForServer("skyrush-1", "skyrush-1"));
        assertTrue(ConfigManager.shouldLoadArenaForServer("SKYRUSH-1", "skyrush-1"));
        assertFalse(ConfigManager.shouldLoadArenaForServer("skyrush-2", "skyrush-1"));
        assertFalse(ConfigManager.shouldLoadArenaForServer("skyrush-1", "lobby"));
        assertFalse(ConfigManager.shouldLoadArenaForServer("", "skyrush-1"));
        assertFalse(ConfigManager.shouldLoadArenaForServer(null, "skyrush-1"));
    }
}
