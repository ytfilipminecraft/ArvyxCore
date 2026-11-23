package sk.arvyx.core.matchmaking;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sk.arvyx.core.arena.Arena;
import sk.arvyx.core.arena.ArenaLocation;
import sk.arvyx.core.arena.ArenaManager;
import sk.arvyx.core.arena.ArenaState;
import sk.arvyx.core.arena.ArenaType;
import sk.arvyx.core.countdown.CountdownManager;
import sk.arvyx.core.game.GameManager;
import sk.arvyx.core.network.ArenaStatusSnapshot;
import sk.arvyx.core.network.LocalNetworkManager;
import sk.arvyx.core.network.NetworkMessage;
import sk.arvyx.core.network.NetworkMessageType;
import sk.arvyx.core.network.ServerInfo;
import sk.arvyx.core.player.PlayerManager;
import sk.arvyx.core.util.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class MatchmakingTest {

    private Logger logger;
    private ArenaManager arenaManager;
    private PlayerManager playerManager;
    private CountdownManager countdownManager;
    private GameManager gameManager;
    private LocalNetworkManager networkManager;
    private MatchmakingManager matchmakingManager;

    @BeforeEach
    void setUp() {
        logger = Logger.createFallback("MatchmakingTest");
        arenaManager = new ArenaManager(logger);
        playerManager = new PlayerManager(arenaManager, logger);
        countdownManager = new CountdownManager(logger);
        gameManager = new GameManager(logger);
        networkManager = new LocalNetworkManager(logger, "skyrush-1", "SKYRUSH", "SkyRush #1", 30);
        networkManager.initialize();
        matchmakingManager = new MatchmakingManager(
                "skyrush-1",
                arenaManager,
                playerManager,
                countdownManager,
                gameManager,
                networkManager,
                logger
        );
    }

    private Arena createArena(String id, ArenaType type, int minPlayers, int maxPlayers, boolean enabled) {
        return new Arena(
                id, "Arena " + id, type, "world_" + id,
                minPlayers, maxPlayers, 10, 240,
                new ArenaLocation("world_" + id, 0, 80, 0),
                new ArenaLocation("world_" + id, 0, 65, 0),
                List.of(), enabled
        );
    }

    @Test
    void selectsBestPopulatedJoinableLocalArenaAndSkipsFullOrDisabled() {
        Arena disabled = createArena("sr-disabled", ArenaType.SKYRUSH, 2, 4, false);
        Arena empty = createArena("sr-empty", ArenaType.SKYRUSH, 2, 4, true);
        Arena populated = createArena("sr-populated", ArenaType.SKYRUSH, 2, 4, true);
        Arena full = createArena("sr-full", ArenaType.SKYRUSH, 1, 1, true);
        full.addPlayer(UUID.randomUUID());
        populated.addPlayer(UUID.randomUUID());

        arenaManager.registerArena(disabled);
        arenaManager.registerArena(empty);
        arenaManager.registerArena(populated);
        arenaManager.registerArena(full);

        Optional<Arena> best = matchmakingManager.selectBestLocalArena(ArenaType.SKYRUSH);
        assertTrue(best.isPresent());
        assertEquals("sr-populated", best.get().getId());
    }

    @Test
    void joinsPlayerStartsCountdownAtMinPlayersAndPreventsDuplicates() {
        Arena arena = createArena("sr-1", ArenaType.SKYRUSH, 2, 4, true);
        arenaManager.registerArena(arena);

        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();

        MatchmakingManager.MatchmakingResult r1 = matchmakingManager.assignToBestLocalArena(p1, ArenaType.SKYRUSH);
        assertEquals(MatchmakingManager.MatchmakingResult.Status.JOINED_LOCAL, r1.status());
        assertFalse(r1.countdownStarted());
        assertEquals(ArenaState.WAITING, arena.getState());

        // Duplicitný pokus rovnakého hráča musí byť odmietnutý
        MatchmakingManager.MatchmakingResult dup = matchmakingManager.assignToBestLocalArena(p1, ArenaType.SKYRUSH);
        assertEquals(MatchmakingManager.MatchmakingResult.Status.ALREADY_IN_ARENA, dup.status());

        // Druhý hráč dosiahne minPlayers -> automatický štart odpočítavania
        MatchmakingManager.MatchmakingResult r2 = matchmakingManager.assignToBestLocalArena(p2, ArenaType.SKYRUSH);
        assertEquals(MatchmakingManager.MatchmakingResult.Status.JOINED_LOCAL, r2.status());
        assertTrue(r2.countdownStarted());
        assertEquals(ArenaState.STARTING, arena.getState());
        assertTrue(countdownManager.isCountingDown("sr-1"));

        // Odpojenie hráča pod minPlayers zruší odpočítavanie
        playerManager.removePlayerFromArena(p2);
        assertEquals(CountdownManager.TickOutcome.CANCELLED_LOW_PLAYERS, countdownManager.tickSession("sr-1"));
        assertEquals(ArenaState.WAITING, arena.getState());
    }

    @Test
    void selectsRemoteOnlineArenaWhenNoLocalArenaAvailable() {
        // Na lokálnom serveri nie je žiadna Chaos aréna
        assertTrue(matchmakingManager.selectBestLocalArena(ArenaType.CHAOS).isEmpty());

        networkManager.registerServer(new ServerInfo("chaos-1", "CHAOS", "Chaos #1", 3, Instant.now(), true));
        networkManager.registerServer(new ServerInfo("chaos-2", "CHAOS", "Chaos #2", 0, Instant.now(), false));

        networkManager.publishArenaStatus(new ArenaStatusSnapshot(
                "chaos-1a", "chaos-1", ArenaType.CHAOS, ArenaState.WAITING, 3, 16, Instant.now()
        ));
        networkManager.publishArenaStatus(new ArenaStatusSnapshot(
                "chaos-2a", "chaos-2", ArenaType.CHAOS, ArenaState.WAITING, 5, 16, Instant.now()
        ));

        Optional<ArenaStatusSnapshot> remote = matchmakingManager.selectBestRemoteArena(ArenaType.CHAOS);
        assertTrue(remote.isPresent());
        assertEquals("chaos-1", remote.get().serverId());
        assertEquals("chaos-1a", remote.get().arenaId());
    }

    @Test
    void reservesRemoteTransferWithoutOverbookingAndRollsBackOnCancel() {
        Instant now = Instant.now();
        networkManager.registerServer(new ServerInfo("chaos-1", "CHAOS", "Chaos #1", 1, now, true));
        networkManager.publishArenaStatus(new ArenaStatusSnapshot(
                "chaos-1a", "chaos-1", ArenaType.CHAOS, ArenaState.WAITING, 1, 2, now
        ));

        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();

        // Prvý hráč obsadí posledné voľné miesto (1 + 1 = 2)
        Optional<ArenaStatusSnapshot> reserved1 = matchmakingManager.reserveRemoteTransfer(p1, ArenaType.CHAOS, now);
        assertTrue(reserved1.isPresent());
        assertEquals("chaos-1a", reserved1.get().arenaId());

        // Druhý hráč už nesmie prebookovať kapacitu
        Optional<ArenaStatusSnapshot> reserved2 = matchmakingManager.reserveRemoteTransfer(p2, ArenaType.CHAOS, now);
        assertTrue(reserved2.isEmpty());

        // Zrušenie rezervácie (napr. pri zlyhaní presunu) uvoľní miesto
        matchmakingManager.cancelOutboundReservation(p1);
        Optional<ArenaStatusSnapshot> reservedAfterCancel = matchmakingManager.reserveRemoteTransfer(p2, ArenaType.CHAOS, now);
        assertTrue(reservedAfterCancel.isPresent());

        // Po expirácii TTL sa rezervácia automaticky vyčistí
        int cleaned = matchmakingManager.cleanupExpiredReservations(now.plusSeconds(MatchmakingManager.RESERVATION_TTL_SECONDS + 1));
        assertEquals(1, cleaned);
    }

    @Test
    void handlesIncomingReservationRequestAndProtectsSlotForReservedPlayer() {
        Arena backendArena = createArena("skyrush-1a", ArenaType.SKYRUSH, 1, 2, true);
        arenaManager.registerArena(backendArena);

        UUID localPlayer = UUID.randomUUID();
        UUID reservedRemotePlayer = UUID.randomUUID();
        UUID unreservedPlayer = UUID.randomUUID();

        assertEquals(MatchmakingManager.MatchmakingResult.Status.JOINED_LOCAL,
                matchmakingManager.assignToSpecificLocalArena(localPlayer, backendArena).status());

        Instant exp = Instant.now().plusSeconds(15);
        NetworkMessage transferMsg = NetworkMessage.direct(
                NetworkMessageType.PLAYER_TRANSFER_REQUEST,
                "skyrush-1",
                "skyrush-1",
                Map.of(
                        "requestId", "test-request-1",
                        "playerUuid", reservedRemotePlayer.toString(),
                        "arenaId", "skyrush-1a",
                        "expiresAtEpochMs", String.valueOf(exp.toEpochMilli())
                )
        );
        AtomicReference<NetworkMessage> dispatched = new AtomicReference<>(transferMsg);
        AtomicReference<NetworkMessage> ack = new AtomicReference<>();
        networkManager.registerHandler(NetworkMessageType.PLAYER_TRANSFER_ACK, ack::set);
        assertTrue(matchmakingManager.handleIncomingReservationRequest(dispatched.get()));
        assertTrue(backendArena.hasValidReservation(reservedRemotePlayer, Instant.now()));
        assertNotNull(ack.get());
        assertEquals("test-request-1", ack.get().getPayloadValue("requestId"));
        assertEquals("true", ack.get().getPayloadValue("accepted"));

        // Nerezervovaný hráč nesmie obsadiť posledné rezervované miesto
        assertEquals(MatchmakingManager.MatchmakingResult.Status.NO_ARENA_AVAILABLE,
                matchmakingManager.assignToBestLocalArena(unreservedPlayer, ArenaType.SKYRUSH).status());

        // Rezervovaný hráč miesto bez problémov obsadí a rezervácia sa spotrebuje
        assertEquals(MatchmakingManager.MatchmakingResult.Status.JOINED_LOCAL,
                matchmakingManager.assignToSpecificLocalArena(reservedRemotePlayer, backendArena).status());
        assertFalse(backendArena.hasValidReservation(reservedRemotePlayer, Instant.now()));
        assertEquals(2, backendArena.getCurrentPlayerCount());
    }
}
