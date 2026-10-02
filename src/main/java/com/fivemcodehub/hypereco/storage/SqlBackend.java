package com.fivemcodehub.hypereco.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * HikariCP-backed persistence. Supports SQLite (default, single node) and
 * MySQL/MariaDB (multi node).
 *
 * <p>Balances are stored as integer minor units (cents), never as floating
 * point. Using a double here is the classic way to leak or duplicate currency
 * through repeated rounding.
 */
public final class SqlBackend {

    private final JavaPlugin plugin;
    private HikariDataSource dataSource;
    private boolean mysql;

    public SqlBackend(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void initialise() throws SQLException {
        var cfg = plugin.getConfig();
        this.mysql = "mysql".equalsIgnoreCase(cfg.getString("storage.type", "sqlite"));

        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("hypereco-pool");

        if (mysql) {
            String host = cfg.getString("storage.mysql.host", "127.0.0.1");
            int port = cfg.getInt("storage.mysql.port", 3306);
            String db = cfg.getString("storage.mysql.database", "minecraft");
            hikari.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + db
                    + "?useSSL=false&allowPublicKeyRetrieval=true&rewriteBatchedStatements=true");
            hikari.setUsername(cfg.getString("storage.mysql.username", "root"));
            hikari.setPassword(cfg.getString("storage.mysql.password", ""));
            hikari.setMaximumPoolSize(cfg.getInt("storage.mysql.pool-size", 10));
        } else {
            java.io.File file = new java.io.File(plugin.getDataFolder(), "economy.db");
            //noinspection ResultOfMethodCallIgnored
            file.getParentFile().mkdirs();
            hikari.setJdbcUrl("jdbc:sqlite:" + file.getAbsolutePath());
            // SQLite serialises writes; more than one connection just adds lock
            // contention and SQLITE_BUSY retries.
            hikari.setMaximumPoolSize(1);
        }

        hikari.setConnectionTimeout(cfg.getLong("storage.connection-timeout-ms", 5_000L));
        hikari.setLeakDetectionThreshold(10_000L);
        this.dataSource = new HikariDataSource(hikari);

        createSchema();
    }

    private void createSchema() throws SQLException {
        String accounts = mysql
                ? """
                  CREATE TABLE IF NOT EXISTS hypereco_accounts (
                    uuid        CHAR(36)    NOT NULL PRIMARY KEY,
                    username    VARCHAR(16) NOT NULL,
                    minor_units BIGINT      NOT NULL DEFAULT 0,
                    updated_at  BIGINT      NOT NULL,
                    INDEX idx_username (username)
                  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                  """
                : """
                  CREATE TABLE IF NOT EXISTS hypereco_accounts (
                    uuid        TEXT    NOT NULL PRIMARY KEY,
                    username    TEXT    NOT NULL,
                    minor_units INTEGER NOT NULL DEFAULT 0,
                    updated_at  INTEGER NOT NULL
                  )
                  """;

        String journal = mysql
                ? """
                  CREATE TABLE IF NOT EXISTS hypereco_journal (
                    id      BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    uuid    CHAR(36)    NOT NULL,
                    delta   BIGINT      NOT NULL,
                    reason  VARCHAR(64) NOT NULL,
                    at      BIGINT      NOT NULL,
                    INDEX idx_uuid_at (uuid, at)
                  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                  """
                : """
                  CREATE TABLE IF NOT EXISTS hypereco_journal (
                    id     INTEGER PRIMARY KEY AUTOINCREMENT,
                    uuid   TEXT    NOT NULL,
                    delta  INTEGER NOT NULL,
                    reason TEXT    NOT NULL,
                    at     INTEGER NOT NULL
                  )
                  """;

        try (Connection c = dataSource.getConnection(); var st = c.createStatement()) {
            st.executeUpdate(accounts);
            st.executeUpdate(journal);
            if (!mysql) {
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_journal_uuid "
                        + "ON hypereco_journal(uuid, at)");
                // WAL lets readers proceed during a write, which matters because
                // the flush worker holds the single connection in bursts.
                st.executeUpdate("PRAGMA journal_mode=WAL");
                st.executeUpdate("PRAGMA synchronous=NORMAL");
            }
        }
    }

    public long loadBalance(UUID uuid, String username, long startingBalance) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT minor_units FROM hypereco_accounts WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return rs.getLong(1);
                }
            }

            // First join: create the account at the configured starting balance.
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO hypereco_accounts (uuid, username, minor_units, updated_at) "
                            + "VALUES (?, ?, ?, ?)")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, username);
                ps.setLong(3, startingBalance);
                ps.setLong(4, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return startingBalance;
        }
    }

    /** Batch-flushes dirty balances. Called only from the write-behind worker. */
    public int flush(Map<UUID, Long> dirty, Map<UUID, String> names) throws SQLException {
        if (dirty.isEmpty()) return 0;

        String sql = mysql
                ? "INSERT INTO hypereco_accounts (uuid, username, minor_units, updated_at) "
                  + "VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE "
                  + "minor_units = VALUES(minor_units), updated_at = VALUES(updated_at)"
                : "INSERT INTO hypereco_accounts (uuid, username, minor_units, updated_at) "
                  + "VALUES (?, ?, ?, ?) ON CONFLICT(uuid) DO UPDATE SET "
                  + "minor_units = excluded.minor_units, updated_at = excluded.updated_at";

        long now = System.currentTimeMillis();
        try (Connection c = dataSource.getConnection()) {
            boolean previousAutoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (Map.Entry<UUID, Long> e : new HashMap<>(dirty).entrySet()) {
                    ps.setString(1, e.getKey().toString());
                    ps.setString(2, names.getOrDefault(e.getKey(), "unknown"));
                    ps.setLong(3, e.getValue());
                    ps.setLong(4, now);
                    ps.addBatch();
                }
                int[] counts = ps.executeBatch();
                c.commit();
                return counts.length;
            } catch (SQLException ex) {
                c.rollback();
                throw ex;
            } finally {
                c.setAutoCommit(previousAutoCommit);
            }
        }
    }

    public void appendJournal(UUID uuid, long delta, String reason) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO hypereco_journal (uuid, delta, reason, at) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, uuid.toString());
            ps.setLong(2, delta);
            ps.setString(3, reason);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    public String describe() {
        return mysql ? "mysql" : "sqlite";
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) dataSource.close();
    }
}
