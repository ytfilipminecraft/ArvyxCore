package sk.arvyx.core.network;

import sk.arvyx.core.arena.ArenaType;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Abstrakcia komunikácie medzi servermi v sieti ARVYX NETWORK.
 * Podporuje lokálny režim aj distribuovanú Redis Pub/Sub synchronizáciu.
 */
public interface NetworkManager {

    void initialize();

    void shutdown();

    ServerInfo getLocalServerInfo();

    void updateLocalHeartbeat(int onlinePlayers);

    void registerServer(ServerInfo serverInfo);

    Optional<ServerInfo> getServer(String serverId);

    Collection<ServerInfo> getRegisteredServers();

    boolean isServerOnline(String serverId);

    int detectUnavailableServers(Instant now);

    void publishArenaStatus(ArenaStatusSnapshot snapshot);

    Collection<ArenaStatusSnapshot> getArenaSnapshots();

    Collection<ArenaStatusSnapshot> getJoinableRemoteArenas(ArenaType type);

    void sendMessage(NetworkMessage message);

    void registerHandler(NetworkMessageType type, Consumer<NetworkMessage> handler);

    boolean isRedisEnabled();

    boolean isRedisConnected();

    NetworkTransportMode getTransportMode();
}
