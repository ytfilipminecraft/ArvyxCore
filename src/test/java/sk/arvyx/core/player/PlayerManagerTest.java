package sk.arvyx.core.player;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sk.arvyx.core.arena.Arena;
import sk.arvyx.core.arena.ArenaLocation;
import sk.arvyx.core.arena.ArenaManager;
import sk.arvyx.core.arena.ArenaType;
import sk.arvyx.core.util.Logger;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PlayerManagerTest {

    private ArenaManager arenaManager;
    private PlayerManager playerManager;
    private Arena arenaOne;
    private Arena arenaTwo;

    @BeforeEach
    void setUp() {
        Logger logger = Logger.createFallback("PlayerManagerTest");
        arenaManager = new ArenaManager(logger);
        playerManager = new PlayerManager(arenaManager, logger);

        arenaOne = new Arena(
                "skyrush-a1", "SkyRush 1", ArenaType.SKYRUSH, "w1",
                2, 2, 10, 240,
                new ArenaLocation("w1", 0, 80, 0),
                new ArenaLocation("w1", 0, 65, 0),
                List.of(), true
        );
        arenaTwo = new Arena(
                "chaos-a1", "Chaos 1", ArenaType.CHAOS, "w2",
                2, 4, 15, 300,
                new ArenaLocation("w2", 0, 80, 0),
                new ArenaLocation("w2", 0, 65, 0),
                List.of(), true
        );
        arenaManager.registerArena(arenaOne);
        arenaManager.registerArena(arenaTwo);
    }

    @Test
    void tracksJoinAndPreventsDuplicateArenaAssignment() {
        UUID playerUuid = UUID.randomUUID();
        PlayerData data = playerManager.handleJoin(playerUuid, "ArvyxPlayer");
        assertEquals(playerUuid, data.getUuid());

        assertEquals(PlayerManager.JoinResult.SUCCESS, playerManager.assignPlayerToArena(playerUuid, arenaOne));
        assertEquals(PlayerManager.JoinResult.ALREADY_IN_THIS_ARENA, playerManager.assignPlayerToArena(playerUuid, arenaOne));
        assertEquals(PlayerManager.JoinResult.ALREADY_IN_OTHER_ARENA, playerManager.assignPlayerToArena(playerUuid, arenaTwo));

        assertEquals("skyrush-a1", playerManager.getCurrentArenaId(playerUuid).orElseThrow());
        assertEquals(0, arenaTwo.getCurrentPlayerCount());
    }

    @Test
    void enforcesArenaCapacityAndFreesPlayerDataOnQuitWithoutMemoryLeak() {
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        UUID p3 = UUID.randomUUID();

        playerManager.handleJoin(p1, "P1");
        playerManager.handleJoin(p2, "P2");
        assertEquals(2, playerManager.getTrackedPlayerCount());

        assertEquals(PlayerManager.JoinResult.SUCCESS, playerManager.assignPlayerToArena(p1, arenaOne));
        assertEquals(PlayerManager.JoinResult.SUCCESS, playerManager.assignPlayerToArena(p2, arenaOne));
        assertEquals(PlayerManager.JoinResult.ARENA_FULL, playerManager.assignPlayerToArena(p3, arenaOne));

        playerManager.handleQuit(p1);
        assertFalse(playerManager.isInArena(p1));
        assertTrue(playerManager.getPlayerData(p1).isEmpty());
        assertEquals(1, arenaOne.getCurrentPlayerCount());
        assertEquals(PlayerManager.JoinResult.SUCCESS, playerManager.assignPlayerToArena(p3, arenaOne));

        playerManager.handleQuit(p2);
        playerManager.handleQuit(p3);
        assertEquals(0, playerManager.getTrackedPlayerCount());
    }

    @Test
    void respectsReservedSlotsDuringArenaAssignment() {
        UUID p1 = UUID.randomUUID();
        UUID reserved = UUID.randomUUID();
        UUID unreserved = UUID.randomUUID();

        assertEquals(PlayerManager.JoinResult.SUCCESS, playerManager.assignPlayerToArena(p1, arenaOne));
        assertTrue(arenaOne.reserveSlot(reserved, Instant.now().plusSeconds(15), Instant.now()));

        // Posledné voľné miesto je rezervované -> nerezervovaný hráč je odmietnutý
        assertEquals(PlayerManager.JoinResult.ARENA_FULL, playerManager.assignPlayerToArena(unreserved, arenaOne));
        // Rezervovaný hráč ho môže okamžite obsadiť
        assertEquals(PlayerManager.JoinResult.SUCCESS, playerManager.assignPlayerToArena(reserved, arenaOne));
        assertFalse(arenaOne.hasValidReservation(reserved, Instant.now()));
    }
}
