package sk.arvyx.core.network;

import java.util.Collection;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Abstrakcia komunikácie medzi servermi v sieti ARVYX NETWORK.
 * Umožňuje bezproblémovú prevádzku bez Redis a čisté pripojenie Redis/API vrstvy v budúcnosti.
 */
public interface NetworkManager {

    void initialize();

    void shutdown();

    ServerInfo getLocalServerInfo();

    void updateLocalHeartbeat(int onlinePlayers);

    void registerServer(ServerInfo serverInfo);

    Optional<ServerInfo> getServer(String serverId);

    Collection<ServerInfo> getRegisteredServers();

    void publishArenaStatus(ArenaStatusSnapshot snapshot);

    Collection<ArenaStatusSnapshot> getArenaSnapshots();

    void sendMessage(NetworkMessage message);

    void registerHandler(NetworkMessageType type, Consumer<NetworkMessage> handler);

    boolean isRedisEnabled();
}
