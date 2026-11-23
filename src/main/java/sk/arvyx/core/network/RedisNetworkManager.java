package sk.arvyx.core.network;

import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.exceptions.JedisException;
import sk.arvyx.core.arena.ArenaType;
import sk.arvyx.core.util.Logger;

import java.time.Instant;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Skutočný distribuovaný NetworkManager využívajúci Redis Pub/Sub.
 * Všetky Redis I/O operácie prebiehajú asynchrónne mimo hlavného Minecraft vlákna.
 * Pri výpadku Redis sa bezpečne prepne do lokálneho fallback režimu a automaticky sa pokúša obnoviť spojenie.
 */
public final class RedisNetworkManager implements NetworkManager {

    private final Logger logger;
    private final RedisConfig redisConfig;
    private final LocalNetworkManager stateStore;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean redisConnected = new AtomicBoolean(false);
    private final AtomicBoolean outageLogged = new AtomicBoolean(false);

    private ExecutorService publishExecutor;
    private Thread subscriberThread;
    private volatile JedisPool jedisPool;
    private volatile JedisPubSub activePubSub;

    public RedisNetworkManager(
            Logger logger,
            String serverId,
            String serverRole,
            String displayName,
            RedisConfig redisConfig
    ) {
        this.logger = Objects.requireNonNull(logger, "Logger nesmie byť null");
        this.redisConfig = Objects.requireNonNull(redisConfig, "RedisConfig nesmie byť null");
        this.stateStore = new LocalNetworkManager(
                logger,
                serverId,
                serverRole,
                displayName,
                redisConfig.serverTimeoutSeconds()
        );
    }

    @Override
    public void initialize() {
        stateStore.initialize();
        if (!redisConfig.enabled()) {
            logger.info("Redis je vypnutý v konfigurácii — aktívny lokálny režim.");
            return;
        }

        running.set(true);
        this.publishExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "arvyx-redis-publisher");
            t.setDaemon(true);
            return t;
        });

        try {
            this.jedisPool = createPool(redisConfig);
        } catch (Exception ex) {
            markRedisUnavailable("Nepodarilo sa vytvoriť Redis pool: " + ex.getMessage());
        }

        this.subscriberThread = new Thread(this::runSubscriberLoop, "arvyx-redis-subscriber");
        this.subscriberThread.setDaemon(true);
        this.subscriberThread.start();
    }

    private static JedisPool createPool(RedisConfig cfg) {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(8);
        poolConfig.setMaxIdle(4);
        poolConfig.setMinIdle(1);
        poolConfig.setTestOnBorrow(true);

        DefaultJedisClientConfig.Builder clientBuilder = DefaultJedisClientConfig.builder()
                .connectionTimeoutMillis(cfg.timeoutMs())
                .socketTimeoutMillis(cfg.timeoutMs())
                .ssl(cfg.ssl());

        if (cfg.password() != null && !cfg.password().isEmpty()) {
            clientBuilder.password(cfg.password());
        }

        return new JedisPool(poolConfig, new HostAndPort(cfg.host(), cfg.port()), clientBuilder.build());
    }

    private void runSubscriberLoop() {
        long backoffMs = 2000L;
        while (running.get()) {
            if (jedisPool == null) {
                try {
                    jedisPool = createPool(redisConfig);
                } catch (Exception ex) {
                    markRedisUnavailable("Redis nedostupný pri opätovnom pripájaní: " + ex.getMessage());
                    if (!sleepQuietly(backoffMs)) {
                        break;
                    }
                    backoffMs = Math.min(20000L, backoffMs * 2);
                    continue;
                }
            }

            try (Jedis subscriberJedis = jedisPool.getResource()) {
                subscriberJedis.ping();
                redisConnected.set(true);
                outageLogged.set(false);
                backoffMs = 2000L;

                logger.info("Redis Pub/Sub pripojené (" + redisConfig.host() + ":" + redisConfig.port()
                        + ", prefix=" + redisConfig.channelPrefix() + ").");

                // Po pripojení okamžite ohlásime prítomnosť servera
                publishServerInfoAsync(stateStore.getLocalServerInfo());

                JedisPubSub pubSub = new JedisPubSub() {
                    @Override
                    public void onMessage(String channel, String message) {
                        handleIncomingPubSubMessage(channel, message);
                    }
                };
                this.activePubSub = pubSub;
                subscriberJedis.subscribe(
                        pubSub,
                        redisConfig.serversChannel(),
                        redisConfig.arenasChannel(),
                        redisConfig.messagesChannel()
                );
            } catch (JedisException ex) {
                if (running.get()) {
                    markRedisUnavailable("Výpadok Redis spojenia: " + ex.getMessage());
                }
            } catch (Exception ex) {
                if (running.get()) {
                    markRedisUnavailable("Neočakávaná chyba Redis odberateľa: " + ex.getMessage());
                }
            } finally {
                this.activePubSub = null;
                redisConnected.set(false);
            }

            if (running.get() && !sleepQuietly(backoffMs)) {
                break;
            }
            backoffMs = Math.min(20000L, backoffMs * 2);
        }
    }

    public void handleIncomingPubSubMessage(String channel, String payload) {
        if (channel == null || payload == null) {
            return;
        }
        String localId = stateStore.getLocalServerInfo().serverId();
        if (channel.equals(redisConfig.serversChannel())) {
            NetworkCodec.decodeServerInfo(payload).ifPresent(info -> {
                if (!info.serverId().equalsIgnoreCase(localId)) {
                    stateStore.registerServer(info);
                }
            });
        } else if (channel.equals(redisConfig.arenasChannel())) {
            NetworkCodec.decodeArenaSnapshot(payload).ifPresent(snapshot -> {
                if (!snapshot.serverId().equalsIgnoreCase(localId)) {
                    stateStore.recordArenaSnapshot(snapshot);
                }
            });
        } else if (channel.equals(redisConfig.messagesChannel())) {
            NetworkCodec.decodeMessage(payload).ifPresent(msg -> {
                if (!msg.getSourceServerId().equalsIgnoreCase(localId)) {
                    stateStore.dispatchLocally(msg);
                }
            });
        }
    }

    private void markRedisUnavailable(String reason) {
        redisConnected.set(false);
        if (outageLogged.compareAndSet(false, true)) {
            logger.warn(reason + " — ArvyxCore pokračuje bezpečne v lokálnom fallback režime.");
        } else {
            logger.debug(reason);
        }
    }

    private static boolean sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public void shutdown() {
        running.set(false);

        if (redisConnected.get() && jedisPool != null && !jedisPool.isClosed()) {
            try (Jedis jedis = jedisPool.getResource()) {
                ServerInfo offlineInfo = new ServerInfo(
                        stateStore.getLocalServerInfo().serverId(),
                        stateStore.getLocalServerInfo().role(),
                        stateStore.getLocalServerInfo().displayName(),
                        0,
                        Instant.now(),
                        false
                );
                jedis.publish(redisConfig.serversChannel(), NetworkCodec.encodeServerInfo(offlineInfo));
            } catch (Exception ex) {
                logger.debug("Nepodarilo sa odoslať SERVER_SHUTDOWN cez Redis pri vypínaní: " + ex.getMessage());
            }
        }

        JedisPubSub pubSub = this.activePubSub;
        if (pubSub != null && pubSub.isSubscribed()) {
            try {
                pubSub.unsubscribe();
            } catch (Exception ignored) {
            }
        }

        if (subscriberThread != null) {
            subscriberThread.interrupt();
        }
        if (publishExecutor != null) {
            publishExecutor.shutdown();
            try {
                if (!publishExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                    publishExecutor.shutdownNow();
                }
            } catch (InterruptedException ex) {
                publishExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        if (jedisPool != null && !jedisPool.isClosed()) {
            jedisPool.close();
        }
        redisConnected.set(false);
        stateStore.shutdown();
    }

    @Override
    public ServerInfo getLocalServerInfo() {
        return stateStore.getLocalServerInfo();
    }

    @Override
    public void updateLocalHeartbeat(int onlinePlayers) {
        stateStore.updateLocalHeartbeat(onlinePlayers);
        publishServerInfoAsync(stateStore.getLocalServerInfo());
    }

    @Override
    public void registerServer(ServerInfo serverInfo) {
        stateStore.registerServer(serverInfo);
    }

    @Override
    public Optional<ServerInfo> getServer(String serverId) {
        return stateStore.getServer(serverId);
    }

    @Override
    public Collection<ServerInfo> getRegisteredServers() {
        return stateStore.getRegisteredServers();
    }

    @Override
    public boolean isServerOnline(String serverId) {
        return stateStore.isServerOnline(serverId);
    }

    @Override
    public int detectUnavailableServers(Instant now) {
        return stateStore.detectUnavailableServers(now);
    }

    @Override
    public void publishArenaStatus(ArenaStatusSnapshot snapshot) {
        stateStore.recordArenaSnapshot(snapshot);
        publishAsync(redisConfig.arenasChannel(), NetworkCodec.encodeArenaSnapshot(snapshot));
    }

    @Override
    public Collection<ArenaStatusSnapshot> getArenaSnapshots() {
        return stateStore.getArenaSnapshots();
    }

    @Override
    public Collection<ArenaStatusSnapshot> getJoinableRemoteArenas(ArenaType type) {
        return stateStore.getJoinableRemoteArenas(type);
    }

    @Override
    public void sendMessage(NetworkMessage message) {
        Objects.requireNonNull(message, "message nesmie byť null");
        stateStore.dispatchLocally(message);
        publishAsync(redisConfig.messagesChannel(), NetworkCodec.encodeMessage(message));
    }

    @Override
    public void registerHandler(NetworkMessageType type, Consumer<NetworkMessage> handler) {
        stateStore.registerHandler(type, handler);
    }

    @Override
    public boolean isRedisEnabled() {
        return redisConfig.enabled();
    }

    @Override
    public boolean isRedisConnected() {
        return redisConnected.get();
    }

    @Override
    public NetworkTransportMode getTransportMode() {
        if (!redisConfig.enabled()) {
            return NetworkTransportMode.LOCAL_ONLY;
        }
        return redisConnected.get()
                ? NetworkTransportMode.REDIS_PUBSUB
                : NetworkTransportMode.REDIS_FALLBACK_LOCAL;
    }

    private void publishServerInfoAsync(ServerInfo info) {
        publishAsync(redisConfig.serversChannel(), NetworkCodec.encodeServerInfo(info));
    }

    private void publishAsync(String channel, String payload) {
        if (!redisConfig.enabled() || !running.get() || publishExecutor == null || publishExecutor.isShutdown()) {
            return;
        }
        publishExecutor.execute(() -> {
            JedisPool pool = this.jedisPool;
            if (pool == null || pool.isClosed()) {
                return;
            }
            try (Jedis jedis = pool.getResource()) {
                jedis.publish(channel, payload);
            } catch (JedisException ex) {
                markRedisUnavailable("Zlyhalo odoslanie Redis správy na kanál " + channel + ": " + ex.getMessage());
            }
        });
    }
}
