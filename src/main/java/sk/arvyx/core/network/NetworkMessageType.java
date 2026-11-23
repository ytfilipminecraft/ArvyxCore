package sk.arvyx.core.network;

/**
 * Typy sieťových správ vymieňaných medzi servermi v rámci ARVYX NETWORK.
 */
public enum NetworkMessageType {
    SERVER_REGISTER,
    SERVER_HEARTBEAT,
    SERVER_SHUTDOWN,
    ARENA_STATUS_UPDATE,
    PLAYER_TRANSFER_REQUEST,
    PLAYER_TRANSFER_ACK,
    CHAOS_STREAM_TRIGGER
}
