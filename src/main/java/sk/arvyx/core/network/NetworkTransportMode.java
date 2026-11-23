package sk.arvyx.core.network;

/**
 * Skutočný prevádzkový režim sieťového transportu.
 * Nikdy nevydáva lokálnu pamäť za distribuovanú sieťovú komunikáciu.
 */
public enum NetworkTransportMode {
    LOCAL_ONLY("Lokálny režim (Redis vypnutý)"),
    REDIS_PUBSUB("Redis Pub/Sub (aktívne pripojené)"),
    REDIS_FALLBACK_LOCAL("Lokálny fallback (Redis nedostupný)");

    private final String description;

    NetworkTransportMode(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
