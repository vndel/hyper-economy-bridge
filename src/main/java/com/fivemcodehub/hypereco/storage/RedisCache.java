package com.fivemcodehub.hypereco.storage;

import org.bukkit.plugin.java.JavaPlugin;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;

import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Cross-server balance invalidation over Redis Pub/Sub.
 *
 * <p>This is deliberately not a cache of record. Only invalidation messages
 * travel over Redis; the SQL row stays authoritative. That keeps a Redis
 * outage degrading to "balances propagate on next join" rather than to
 * "players lose money".
 */
public final class RedisCache {

    private static final String CHANNEL = "hypereco:invalidate";

    private final JavaPlugin plugin;
    private JedisPool pool;
    private JedisPubSub subscriber;
    private Thread subscriberThread;
    private volatile boolean closing;

    public RedisCache(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void connect() {
        var cfg = plugin.getConfig();
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(cfg.getInt("redis.pool-size", 8));
        poolConfig.setMaxIdle(4);
        poolConfig.setMinIdle(1);
        poolConfig.setTestOnBorrow(true);

        String host = cfg.getString("redis.host", "127.0.0.1");
        int port = cfg.getInt("redis.port", 6379);
        int timeout = cfg.getInt("redis.timeout-ms", 2_000);
        String password = cfg.getString("redis.password", "");

        this.pool = password.isEmpty()
                ? new JedisPool(poolConfig, host, port, timeout)
                : new JedisPool(poolConfig, host, port, timeout, password);

        plugin.getLogger().info("Redis pool opened -> " + host + ":" + port);
    }

    /** Subscribes on a dedicated thread; Jedis subscribe() blocks by design. */
    public void subscribe(BiConsumer<UUID, Long> onInvalidate) {
        this.subscriber = new JedisPubSub() {
            @Override
            public void onMessage(String channel, String message) {
                // payload: <uuid>:<minorUnits>
                int split = message.indexOf(':');
                if (split <= 0) return;
                try {
                    UUID uuid = UUID.fromString(message.substring(0, split));
                    long units = Long.parseLong(message.substring(split + 1));
                    onInvalidate.accept(uuid, units);
                } catch (IllegalArgumentException ignored) {
                    // Malformed payload from another node; dropping is correct.
                }
            }
        };

        this.subscriberThread = new Thread(() -> {
            while (!closing) {
                try (var jedis = pool.getResource()) {
                    jedis.subscribe(subscriber, CHANNEL);
                } catch (Exception ex) {
                    if (closing) return;
                    plugin.getLogger().warning("Redis subscriber dropped: " + ex.getMessage());
                    try {
                        Thread.sleep(5_000L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }, "hypereco-redis-sub");

        subscriberThread.setDaemon(true);
        subscriberThread.start();
    }

    public void publishInvalidation(UUID uuid, long minorUnits) {
        if (pool == null || pool.isClosed()) return;
        try (var jedis = pool.getResource()) {
            jedis.publish(CHANNEL, uuid + ":" + minorUnits);
        } catch (Exception ex) {
            // Never propagate: a failed invalidation must not fail the transaction.
            plugin.getLogger().fine("Invalidation publish failed: " + ex.getMessage());
        }
    }

    public void close() {
        this.closing = true;
        if (subscriber != null) {
            try {
                subscriber.unsubscribe();
            } catch (Exception ignored) {
                // Already disconnected.
            }
        }
        if (subscriberThread != null) subscriberThread.interrupt();
        if (pool != null && !pool.isClosed()) pool.close();
    }
}
