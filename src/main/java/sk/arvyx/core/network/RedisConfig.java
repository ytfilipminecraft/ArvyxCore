package sk.arvyx.core.network;

/**
 * Konfigurácia pripojenia k Redis Pub/Sub pre sieť ARVYX NETWORK.
 */
public record RedisConfig(
        boolean enabled,
        String host,
        int port,
        boolean ssl,
        int timeoutMs,
        String password,
        String channelPrefix,
        int serverTimeoutSeconds
) {
    public RedisConfig {
        host = (host == null || host.isBlank()) ? "127.0.0.1" : host.trim();
        port = (port <= 0 || port > 65535) ? 6379 : port;
        timeoutMs = Math.clamp(timeoutMs, 250, 15000);
        password = (password == null) ? "" : password;
        channelPrefix = (channelPrefix == null || channelPrefix.isBlank()) ? "arvyx:network" : channelPrefix.trim();
        serverTimeoutSeconds = Math.max(10, serverTimeoutSeconds);
    }

    public static RedisConfig disabled() {
        return new RedisConfig(false, "127.0.0.1", 6379, false, 2000, "", "arvyx:network", 30);
    }

    public String serversChannel() {
        return channelPrefix + ":servers";
    }

    public String arenasChannel() {
        return channelPrefix + ":arenas";
    }

    public String messagesChannel() {
        return channelPrefix + ":messages";
    }
}
