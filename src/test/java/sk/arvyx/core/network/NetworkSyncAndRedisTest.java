package sk.arvyx.core.network;

import org.junit.jupiter.api.Test;
import sk.arvyx.core.arena.ArenaState;
import sk.arvyx.core.arena.ArenaType;
import sk.arvyx.core.util.Logger;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class NetworkSyncAndRedisTest {

    @Test
    void encodesAndDecodesNetworkPayloadsSafely() {
        ServerInfo info = new ServerInfo("skyrush-2", "SKYRUSH", "SkyRush #2", 6, Instant.ofEpochMilli(1700000000000L), true);
        ServerInfo decodedServer = NetworkCodec.decodeServerInfo(NetworkCodec.encodeServerInfo(info)).orElseThrow();
        assertEquals(info, decodedServer);

        ArenaStatusSnapshot snap = new ArenaStatusSnapshot(
                "skyrush-2a", "skyrush-2", ArenaType.SKYRUSH, ArenaState.STARTING, 6, 8, Instant.ofEpochMilli(1700000001000L)
        );
        ArenaStatusSnapshot decodedSnap = NetworkCodec.decodeArenaSnapshot(NetworkCodec.encodeArenaSnapshot(snap)).orElseThrow();
        assertEquals(snap, decodedSnap);

        NetworkMessage msg = NetworkMessage.broadcast(
                NetworkMessageType.CHAOS_STREAM_TRIGGER, "lobby", Map.of("event", "LOW_GRAVITY")
        );
        NetworkMessage decodedMsg = NetworkCodec.decodeMessage(NetworkCodec.encodeMessage(msg)).orElseThrow();
        assertEquals(msg.getType(), decodedMsg.getType());
        assertEquals("LOW_GRAVITY", decodedMsg.getPayloadValue("event"));

        // Poškodený JSON nesmie vyhodiť výnimku
        assertTrue(NetworkCodec.decodeArenaSnapshot("{invalid_json").isEmpty());
        assertTrue(NetworkCodec.decodeServerInfo("").isEmpty());
        assertTrue(NetworkCodec.decodeMessage(null).isEmpty());
    }

    @Test
    void synchronizesRemoteServersAndDetectsUnavailableServerTimeout() {
        Logger logger = Logger.createFallback("NetworkSyncTest");
        RedisConfig cfg = new RedisConfig(false, "127.0.0.1", 6379, false, 1000, "", "arvyx:test", 30);
        RedisNetworkManager manager = new RedisNetworkManager(logger, "lobby", "LOBBY", "Lobby", cfg);
        manager.initialize();
        assertEquals(NetworkTransportMode.LOCAL_ONLY, manager.getTransportMode());

        Instant t0 = Instant.now();
        ServerInfo remoteInfo = new ServerInfo("skyrush-1", "SKYRUSH", "SkyRush #1", 4, t0, true);
        ArenaStatusSnapshot remoteSnap = new ArenaStatusSnapshot(
                "skyrush-1a", "skyrush-1", ArenaType.SKYRUSH, ArenaState.WAITING, 4, 8, t0
        );

        manager.handleIncomingPubSubMessage(cfg.serversChannel(), NetworkCodec.encodeServerInfo(remoteInfo));
        manager.handleIncomingPubSubMessage(cfg.arenasChannel(), NetworkCodec.encodeArenaSnapshot(remoteSnap));

        assertTrue(manager.isServerOnline("skyrush-1"));
        assertEquals(1, manager.getJoinableRemoteArenas(ArenaType.SKYRUSH).size());

        // Po uplynutí timeoutu bez heartbeatu sa server označí ako nedostupný a jeho arény sa vyradia
        int offlineCount = manager.detectUnavailableServers(t0.plusSeconds(35));
        assertEquals(1, offlineCount);
        assertFalse(manager.isServerOnline("skyrush-1"));
        assertTrue(manager.getJoinableRemoteArenas(ArenaType.SKYRUSH).isEmpty());

        manager.shutdown();
    }

    @Test
    void handlesRedisOutageGracefullyInFallbackMode() {
        Logger logger = Logger.createFallback("RedisOutageTest");
        // Port 59999 nemá spustený Redis -> simulácia výpadku Redis servera
        RedisConfig unreachableCfg = new RedisConfig(true, "127.0.0.1", 59999, false, 300, "", "arvyx:outage", 30);
        RedisNetworkManager manager = new RedisNetworkManager(logger, "chaos-1", "CHAOS", "Chaos #1", unreachableCfg);

        AtomicReference<NetworkMessage> received = new AtomicReference<>();
        manager.registerHandler(NetworkMessageType.ARENA_STATUS_UPDATE, received::set);

        assertDoesNotThrow(manager::initialize);
        assertTrue(manager.isRedisEnabled());
        assertFalse(manager.isRedisConnected());
        assertEquals(NetworkTransportMode.REDIS_FALLBACK_LOCAL, manager.getTransportMode());

        // Lokálne operácie musia fungovať aj počas výpadku Redis
        assertDoesNotThrow(() -> manager.updateLocalHeartbeat(5));
        assertDoesNotThrow(() -> manager.publishArenaStatus(new ArenaStatusSnapshot(
                "chaos-1a", "chaos-1", ArenaType.CHAOS, ArenaState.WAITING, 2, 16, Instant.now()
        )));
        assertEquals(1, manager.getArenaSnapshots().size());

        manager.sendMessage(NetworkMessage.broadcast(NetworkMessageType.ARENA_STATUS_UPDATE, "chaos-1", Map.of("ok", "true")));
        assertNotNull(received.get());

        assertDoesNotThrow(manager::shutdown);
    }

    @Test
    void routesIncomingPubSubDirectTransferRequestAndIgnoresSelfMessages() {
        Logger logger = Logger.createFallback("PubSubRoutingTest");
        RedisConfig cfg = new RedisConfig(false, "127.0.0.1", 6379, false, 1000, "", "arvyx:test", 30);
        RedisNetworkManager manager = new RedisNetworkManager(logger, "skyrush-1", "SKYRUSH", "SkyRush #1", cfg);
        manager.initialize();
        manager.setOnReconnectCallback(() -> {});

        AtomicReference<NetworkMessage> received = new AtomicReference<>();
        manager.registerHandler(NetworkMessageType.PLAYER_TRANSFER_REQUEST, received::set);

        NetworkMessage fromLobby = NetworkMessage.direct(
                NetworkMessageType.PLAYER_TRANSFER_REQUEST,
                "lobby",
                "skyrush-1",
                Map.of("arenaId", "skyrush-1a", "playerUuid", "00000000-0000-0000-0000-000000000001")
        );
        manager.handleIncomingPubSubMessage(cfg.messagesChannel(), NetworkCodec.encodeMessage(fromLobby));
        assertNotNull(received.get());
        assertEquals("skyrush-1a", received.get().getPayloadValue("arenaId"));

        // Správa s vlastným sourceServerId sa musí ignorovať pri spätnom Pub/Sub doručení
        received.set(null);
        NetworkMessage echoSelf = NetworkMessage.direct(
                NetworkMessageType.PLAYER_TRANSFER_REQUEST,
                "skyrush-1",
                "skyrush-1",
                Map.of("arenaId", "skyrush-1a")
        );
        manager.handleIncomingPubSubMessage(cfg.messagesChannel(), NetworkCodec.encodeMessage(echoSelf));
        assertNull(received.get());

        manager.shutdown();
    }
}
