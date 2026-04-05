package com.farmerplots;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * 🛸 PROJECT OVERLORD: FarmerPlots Centurion Engine
 * <p>
 * Production-grade Spigot/Paper 1.20.6+ plugin (Java 17+ bytecode, Java 21 required at runtime).
 * Single-file implementation containing all core systems:
 * <ul>
 *   <li>{@link DatabaseManager}   – async HikariCP + SQLite persistence with ConcurrentHashMap cache</li>
 *   <li>{@link FarmerPlotsExpansion} – PlaceholderAPI expansion</li>
 *   <li>Data records: {@link PlotData}, {@link TrustEntry}, {@link DeliveryVaultEntry},
 *       {@link PlotSetting}, {@link PlotAnalytics}</li>
 * </ul>
 */
public final class FarmerPlotsPlugin extends JavaPlugin {

    // ─────────────────────────────────────────────────────────────────────────
    // 1.  DATA RECORDS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Immutable snapshot of a row from {@code fp_plots}.
     *
     * @param plotId    Primary key (e.g. "world_0_0")
     * @param ownerUuid Owner's {@link UUID} as a String
     * @param ownerName Display name at time of creation
     * @param worldName Bukkit world name
     * @param createdAt Unix epoch milliseconds
     * @param lastSeen  Unix epoch milliseconds of last owner activity
     * @param plotSize  Edge length of the square plot in blocks
     */
    public record PlotData(
            String plotId,
            String ownerUuid,
            String ownerName,
            String worldName,
            long createdAt,
            long lastSeen,
            int plotSize
    ) {}

    /**
     * Trust entry linking a guest to a plot with a permission level.
     *
     * @param plotId     FK to {@code fp_plots.plot_id}
     * @param guestUuid  Guest's {@link UUID} as a String
     * @param trustLevel 0 = none, 1 = visitor, 2 = builder, 3 = manager, 4 = co-owner
     */
    public record TrustEntry(
            String plotId,
            String guestUuid,
            int trustLevel
    ) {}

    /**
     * A delivery-vault item waiting to be claimed.
     *
     * @param uuid      Owner UUID (PK)
     * @param itemBlob  Base64-encoded NBT byte array
     * @param expiresAt Unix epoch milliseconds after which the entry should be purged
     */
    public record DeliveryVaultEntry(
            String uuid,
            String itemBlob,
            long expiresAt
    ) {}

    /**
     * A single key/value configuration entry for a plot.
     *
     * @param plotId     FK to {@code fp_plots.plot_id}
     * @param settingKey Dot-separated key (e.g. "pvp.enabled")
     * @param settingVal String-encoded value
     */
    public record PlotSetting(
            String plotId,
            String settingKey,
            String settingVal
    ) {}

    /**
     * Aggregate analytics counters for a single plot.
     *
     * @param plotId           FK to {@code fp_plots.plot_id}
     * @param totalBlocksPlaced Cumulative blocks placed inside the plot
     * @param totalGensActive   Current number of active generators inside the plot
     */
    public record PlotAnalytics(
            String plotId,
            int totalBlocksPlaced,
            int totalGensActive
    ) {}

    // ─────────────────────────────────────────────────────────────────────────
    // 2.  DATABASE MANAGER
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Manages all persistence for FarmerPlots using HikariCP + SQLite.
     * <p>
     * All public methods that touch the database return a {@link CompletableFuture}
     * and execute their SQL off the main thread.  Write operations also update
     * an in-memory {@link ConcurrentHashMap} cache so that hot-path read calls
     * (e.g. permission checks during block-break events) never block.
     *
     * <h3>Schema (5 tables)</h3>
     * <pre>
     * fp_plots          – per-plot metadata
     * fp_trust          – per-plot trust roster
     * fp_delivery_vault – cross-server item mailbox
     * fp_settings       – per-plot key/value config
     * fp_analytics      – per-plot block / generator counters
     * </pre>
     */
    public static final class DatabaseManager {

        // ── Pool settings ────────────────────────────────────────────────────
        private static final int MAX_POOL_SIZE = 10;
        private static final long CONNECTION_TIMEOUT_MS = 5_000L;
        private static final long IDLE_TIMEOUT_MS = 600_000L;
        private static final long MAX_LIFETIME_MS = 1_800_000L;
        private static final long KEEPALIVE_TIME_MS = 30_000L;

        // ── In-memory caches ─────────────────────────────────────────────────
        /** Primary plot cache: plotId → PlotData */
        private final ConcurrentHashMap<String, PlotData> plotCache = new ConcurrentHashMap<>();
        /** Trust cache: plotId → list of TrustEntry */
        private final ConcurrentHashMap<String, List<TrustEntry>> trustCache = new ConcurrentHashMap<>();
        /** Settings cache: plotId → (key → value) */
        private final ConcurrentHashMap<String, ConcurrentHashMap<String, String>> settingsCache = new ConcurrentHashMap<>();
        /** Analytics cache: plotId → PlotAnalytics */
        private final ConcurrentHashMap<String, PlotAnalytics> analyticsCache = new ConcurrentHashMap<>();
        /** Delivery-vault cache: ownerUuid → DeliveryVaultEntry */
        private final ConcurrentHashMap<String, DeliveryVaultEntry> vaultCache = new ConcurrentHashMap<>();

        private final FarmerPlotsPlugin plugin;
        private HikariDataSource dataSource;

        public DatabaseManager(FarmerPlotsPlugin plugin) {
            this.plugin = plugin;
        }

        // ── Lifecycle ────────────────────────────────────────────────────────

        /**
         * Initialises HikariCP and creates all tables if they do not yet exist.
         * Must be called synchronously during {@code onEnable()}.
         */
        public void init() {
            File dataFolder = plugin.getDataFolder();
            if (!dataFolder.exists() && !dataFolder.mkdirs()) {
                plugin.getLogger().severe("Could not create plugin data folder: " + dataFolder.getAbsolutePath());
                return;
            }

            File dbFile = new File(dataFolder, "farmerplots.db");

            HikariConfig config = new HikariConfig();
            config.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
            config.setDriverClassName("org.sqlite.JDBC");
            config.setMaximumPoolSize(MAX_POOL_SIZE);
            config.setConnectionTimeout(CONNECTION_TIMEOUT_MS);
            config.setIdleTimeout(IDLE_TIMEOUT_MS);
            config.setMaxLifetime(MAX_LIFETIME_MS);
            config.setKeepaliveTime(KEEPALIVE_TIME_MS);
            config.setPoolName("FarmerPlots-Pool");

            // SQLite-specific optimisations
            config.addDataSourceProperty("journal_mode", "WAL");
            config.addDataSourceProperty("synchronous", "NORMAL");
            config.addDataSourceProperty("foreign_keys", "ON");

            dataSource = new HikariDataSource(config);
            createTables();
            plugin.getLogger().info("DatabaseManager initialised – pool size " + MAX_POOL_SIZE);
        }

        /** Gracefully closes the HikariCP pool. */
        public void shutdown() {
            if (dataSource != null && !dataSource.isClosed()) {
                dataSource.close();
                plugin.getLogger().info("DatabaseManager pool closed.");
            }
        }

        /** Opens a connection from the pool. */
        private Connection getConnection() throws SQLException {
            return dataSource.getConnection();
        }

        // ── DDL ──────────────────────────────────────────────────────────────

        private void createTables() {
            String fpPlots = """
                    CREATE TABLE IF NOT EXISTS fp_plots (
                        plot_id    TEXT    NOT NULL PRIMARY KEY,
                        owner_uuid TEXT    NOT NULL,
                        owner_name TEXT    NOT NULL,
                        world_name TEXT    NOT NULL,
                        created_at INTEGER NOT NULL,
                        last_seen  INTEGER NOT NULL,
                        plot_size  INTEGER NOT NULL DEFAULT 64
                    );
                    """;

            String fpPlotsIdx = """
                    CREATE INDEX IF NOT EXISTS idx_fp_plots_owner
                        ON fp_plots (owner_uuid);
                    """;

            String fpTrust = """
                    CREATE TABLE IF NOT EXISTS fp_trust (
                        plot_id     TEXT    NOT NULL,
                        guest_uuid  TEXT    NOT NULL,
                        trust_level INTEGER NOT NULL CHECK (trust_level BETWEEN 0 AND 4),
                        PRIMARY KEY (plot_id, guest_uuid),
                        FOREIGN KEY (plot_id) REFERENCES fp_plots (plot_id) ON DELETE CASCADE
                    );
                    """;

            String fpDeliveryVault = """
                    CREATE TABLE IF NOT EXISTS fp_delivery_vault (
                        uuid       TEXT NOT NULL PRIMARY KEY,
                        item_blob  TEXT NOT NULL,
                        expires_at INTEGER NOT NULL
                    );
                    """;

            String fpSettings = """
                    CREATE TABLE IF NOT EXISTS fp_settings (
                        plot_id     TEXT NOT NULL,
                        setting_key TEXT NOT NULL,
                        setting_val TEXT NOT NULL,
                        PRIMARY KEY (plot_id, setting_key),
                        FOREIGN KEY (plot_id) REFERENCES fp_plots (plot_id) ON DELETE CASCADE
                    );
                    """;

            String fpAnalytics = """
                    CREATE TABLE IF NOT EXISTS fp_analytics (
                        plot_id              TEXT    NOT NULL PRIMARY KEY,
                        total_blocks_placed  INTEGER NOT NULL DEFAULT 0,
                        total_gens_active    INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY (plot_id) REFERENCES fp_plots (plot_id) ON DELETE CASCADE
                    );
                    """;

            try (Connection conn = getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.executeUpdate(fpPlots);
                stmt.executeUpdate(fpPlotsIdx);
                stmt.executeUpdate(fpTrust);
                stmt.executeUpdate(fpDeliveryVault);
                stmt.executeUpdate(fpSettings);
                stmt.executeUpdate(fpAnalytics);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to create database tables", e);
            }
        }

        // ── fp_plots CRUD ────────────────────────────────────────────────────

        /**
         * Asynchronously loads a plot from the database and populates the cache.
         *
         * @param plotId the plot's primary key
         * @return a future resolving to an {@link Optional} containing the plot, or empty
         */
        public CompletableFuture<Optional<PlotData>> loadPlot(String plotId) {
            Objects.requireNonNull(plotId, "plotId");

            PlotData cached = plotCache.get(plotId);
            if (cached != null) {
                return CompletableFuture.completedFuture(Optional.of(cached));
            }

            return CompletableFuture.supplyAsync(() -> {
                final String sql = "SELECT plot_id, owner_uuid, owner_name, world_name, "
                        + "created_at, last_seen, plot_size FROM fp_plots WHERE plot_id = ?";
                try (Connection conn = getConnection();
                     PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, plotId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            PlotData plot = rowToPlotData(rs);
                            plotCache.put(plotId, plot);
                            return Optional.of(plot);
                        }
                    }
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.SEVERE, "loadPlot failed for " + plotId, e);
                }
                return Optional.empty();
            });
        }

        /**
         * Asynchronously persists (insert-or-replace) a plot and updates the cache.
         *
         * @param plot the {@link PlotData} to save
         * @return a future that completes when the write is done
         */
        public CompletableFuture<Void> savePlot(PlotData plot) {
            Objects.requireNonNull(plot, "plot");
            plotCache.put(plot.plotId(), plot);

            return CompletableFuture.runAsync(() -> {
                final String sql = """
                        INSERT INTO fp_plots
                            (plot_id, owner_uuid, owner_name, world_name, created_at, last_seen, plot_size)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT(plot_id) DO UPDATE SET
                            owner_uuid = excluded.owner_uuid,
                            owner_name = excluded.owner_name,
                            world_name = excluded.world_name,
                            last_seen  = excluded.last_seen,
                            plot_size  = excluded.plot_size
                        """;
                try (Connection conn = getConnection();
                     PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, plot.plotId());
                    ps.setString(2, plot.ownerUuid());
                    ps.setString(3, plot.ownerName());
                    ps.setString(4, plot.worldName());
                    ps.setLong(5, plot.createdAt());
                    ps.setLong(6, plot.lastSeen());
                    ps.setInt(7, plot.plotSize());
                    ps.executeUpdate();
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.SEVERE, "savePlot failed for " + plot.plotId(), e);
                }
            });
        }

        /**
         * Asynchronously deletes a plot and all associated rows (cascade) and removes it from the cache.
         *
         * @param plotId the plot to delete
         * @return a future that completes when the delete is done
         */
        public CompletableFuture<Void> deletePlot(String plotId) {
            Objects.requireNonNull(plotId, "plotId");
            plotCache.remove(plotId);
            trustCache.remove(plotId);
            settingsCache.remove(plotId);
            analyticsCache.remove(plotId);

            return CompletableFuture.runAsync(() -> {
                final String sql = "DELETE FROM fp_plots WHERE plot_id = ?";
                try (Connection conn = getConnection();
                     PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, plotId);
                    ps.executeUpdate();
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.SEVERE, "deletePlot failed for " + plotId, e);
                }
            });
        }

        /**
         * Asynchronously retrieves all plots owned by the given UUID.
         *
         * @param ownerUuid the owner's {@link UUID} as a String
         * @return a future resolving to an unmodifiable list of {@link PlotData}
         */
        public CompletableFuture<List<PlotData>> getPlotsByOwner(String ownerUuid) {
            Objects.requireNonNull(ownerUuid, "ownerUuid");

            return CompletableFuture.supplyAsync(() -> {
                final String sql = "SELECT plot_id, owner_uuid, owner_name, world_name, "
                        + "created_at, last_seen, plot_size FROM fp_plots WHERE owner_uuid = ?";
                List<PlotData> results = new ArrayList<>();
                try (Connection conn = getConnection();
                     PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, ownerUuid);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            PlotData plot = rowToPlotData(rs);
                            plotCache.put(plot.plotId(), plot);
                            results.add(plot);
                        }
                    }
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.SEVERE, "getPlotsByOwner failed for " + ownerUuid, e);
                }
                return Collections.unmodifiableList(results);
            });
        }

        // ── fp_trust CRUD ────────────────────────────────────────────────────

        /**
         * Asynchronously sets the trust level for a guest on a plot (insert-or-replace).
         * A {@code trustLevel} of 0 removes the entry.
         *
         * @param plotId     FK to {@code fp_plots}
         * @param guestUuid  guest's UUID as a String
         * @param trustLevel 0–4
         * @return a future that completes when the write is done
         */
        public CompletableFuture<Void> saveTrust(String plotId, String guestUuid, int trustLevel) {
            Objects.requireNonNull(plotId, "plotId");
            Objects.requireNonNull(guestUuid, "guestUuid");
            if (trustLevel < 0 || trustLevel > 4) {
                throw new IllegalArgumentException("trustLevel must be between 0 and 4, got: " + trustLevel);
            }

            // Update in-memory cache
            trustCache.compute(plotId, (k, list) -> {
                List<TrustEntry> updated = list == null ? new ArrayList<>() : new ArrayList<>(list);
                updated.removeIf(e -> e.guestUuid().equals(guestUuid));
                if (trustLevel > 0) {
                    updated.add(new TrustEntry(plotId, guestUuid, trustLevel));
                }
                return Collections.unmodifiableList(updated);
            });

            return CompletableFuture.runAsync(() -> {
                if (trustLevel == 0) {
                    final String sql = "DELETE FROM fp_trust WHERE plot_id = ? AND guest_uuid = ?";
                    try (Connection conn = getConnection();
                         PreparedStatement ps = conn.prepareStatement(sql)) {
                        ps.setString(1, plotId);
                        ps.setString(2, guestUuid);
                        ps.executeUpdate();
                    } catch (SQLException e) {
                        plugin.getLogger().log(Level.SEVERE, "saveTrust(delete) failed", e);
                    }
                } else {
                    final String sql = """
                            INSERT INTO fp_trust (plot_id, guest_uuid, trust_level)
                            VALUES (?, ?, ?)
                            ON CONFLICT(plot_id, guest_uuid) DO UPDATE SET trust_level = excluded.trust_level
                            """;
                    try (Connection conn = getConnection();
                         PreparedStatement ps = conn.prepareStatement(sql)) {
                        ps.setString(1, plotId);
                        ps.setString(2, guestUuid);
                        ps.setInt(3, trustLevel);
                        ps.executeUpdate();
                    } catch (SQLException e) {
                        plugin.getLogger().log(Level.SEVERE, "saveTrust(upsert) failed", e);
                    }
                }
            });
        }

        /**
         * Asynchronously retrieves all trust entries for a plot.
         * Returns the cached list if available; otherwise queries the database.
         *
         * @param plotId FK to {@code fp_plots}
         * @return a future resolving to an unmodifiable list of {@link TrustEntry}
         */
        public CompletableFuture<List<TrustEntry>> getTrust(String plotId) {
            Objects.requireNonNull(plotId, "plotId");

            List<TrustEntry> cached = trustCache.get(plotId);
            if (cached != null) {
                return CompletableFuture.completedFuture(cached);
            }

            return CompletableFuture.supplyAsync(() -> {
                final String sql = "SELECT plot_id, guest_uuid, trust_level FROM fp_trust WHERE plot_id = ?";
                List<TrustEntry> results = new ArrayList<>();
                try (Connection conn = getConnection();
                     PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, plotId);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            results.add(new TrustEntry(
                                    rs.getString("plot_id"),
                                    rs.getString("guest_uuid"),
                                    rs.getInt("trust_level")
                            ));
                        }
                    }
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.SEVERE, "getTrust failed for " + plotId, e);
                }
                List<TrustEntry> immutable = Collections.unmodifiableList(results);
                trustCache.put(plotId, immutable);
                return immutable;
            });
        }

        // ── fp_delivery_vault CRUD ───────────────────────────────────────────

        /**
         * Asynchronously stores a delivery-vault entry (insert-or-replace).
         *
         * @param ownerUuid the owner UUID (PK)
         * @param itemBlob  Base64-encoded NBT byte array
         * @param expiresAt Unix epoch milliseconds
         * @return a future that completes when the write is done
         */
        public CompletableFuture<Void> saveDeliveryVault(String ownerUuid, String itemBlob, long expiresAt) {
            Objects.requireNonNull(ownerUuid, "ownerUuid");
            Objects.requireNonNull(itemBlob, "itemBlob");

            DeliveryVaultEntry entry = new DeliveryVaultEntry(ownerUuid, itemBlob, expiresAt);
            vaultCache.put(ownerUuid, entry);

            return CompletableFuture.runAsync(() -> {
                final String sql = """
                        INSERT INTO fp_delivery_vault (uuid, item_blob, expires_at)
                        VALUES (?, ?, ?)
                        ON CONFLICT(uuid) DO UPDATE SET item_blob = excluded.item_blob,
                                                        expires_at = excluded.expires_at
                        """;
                try (Connection conn = getConnection();
                     PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, ownerUuid);
                    ps.setString(2, itemBlob);
                    ps.setLong(3, expiresAt);
                    ps.executeUpdate();
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.SEVERE, "saveDeliveryVault failed for " + ownerUuid, e);
                }
            });
        }

        /**
         * Asynchronously retrieves a delivery-vault entry for the given owner UUID.
         *
         * @param ownerUuid the owner UUID (PK)
         * @return a future resolving to an {@link Optional} containing the entry, or empty
         */
        public CompletableFuture<Optional<DeliveryVaultEntry>> getDeliveryVault(String ownerUuid) {
            Objects.requireNonNull(ownerUuid, "ownerUuid");

            DeliveryVaultEntry cached = vaultCache.get(ownerUuid);
            if (cached != null) {
                return CompletableFuture.completedFuture(Optional.of(cached));
            }

            return CompletableFuture.supplyAsync(() -> {
                final String sql = "SELECT uuid, item_blob, expires_at FROM fp_delivery_vault WHERE uuid = ?";
                try (Connection conn = getConnection();
                     PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, ownerUuid);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            DeliveryVaultEntry entry = new DeliveryVaultEntry(
                                    rs.getString("uuid"),
                                    rs.getString("item_blob"),
                                    rs.getLong("expires_at")
                            );
                            vaultCache.put(ownerUuid, entry);
                            return Optional.of(entry);
                        }
                    }
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.SEVERE, "getDeliveryVault failed for " + ownerUuid, e);
                }
                return Optional.empty();
            });
        }

        /**
         * Asynchronously deletes a delivery-vault entry and removes it from the cache.
         *
         * @param ownerUuid the owner UUID (PK)
         * @return a future that completes when the delete is done
         */
        public CompletableFuture<Void> deleteDeliveryVault(String ownerUuid) {
            Objects.requireNonNull(ownerUuid, "ownerUuid");
            vaultCache.remove(ownerUuid);

            return CompletableFuture.runAsync(() -> {
                final String sql = "DELETE FROM fp_delivery_vault WHERE uuid = ?";
                try (Connection conn = getConnection();
                     PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, ownerUuid);
                    ps.executeUpdate();
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.SEVERE, "deleteDeliveryVault failed for " + ownerUuid, e);
                }
            });
        }

        /**
         * Asynchronously purges all expired delivery-vault entries.
         * Should be called periodically (e.g. on server startup or via a scheduler).
         *
         * @return a future that completes when the purge is done
         */
        public CompletableFuture<Void> purgeExpiredVaultEntries() {
            long now = System.currentTimeMillis();
            // Remove expired entries from cache
            vaultCache.entrySet().removeIf(e -> e.getValue().expiresAt() < now);

            return CompletableFuture.runAsync(() -> {
                final String sql = "DELETE FROM fp_delivery_vault WHERE expires_at < ?";
                try (Connection conn = getConnection();
                     PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setLong(1, now);
                    int rows = ps.executeUpdate();
                    if (rows > 0) {
                        plugin.getLogger().info("Purged " + rows + " expired delivery-vault entries.");
                    }
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.SEVERE, "purgeExpiredVaultEntries failed", e);
                }
            });
        }

        // ── fp_settings CRUD ─────────────────────────────────────────────────

        /**
         * Asynchronously persists a single plot setting (insert-or-replace) and updates the cache.
         *
         * @param plotId      FK to {@code fp_plots}
         * @param settingKey  dot-separated key
         * @param settingVal  string-encoded value
         * @return a future that completes when the write is done
         */
        public CompletableFuture<Void> saveSetting(String plotId, String settingKey, String settingVal) {
            Objects.requireNonNull(plotId, "plotId");
            Objects.requireNonNull(settingKey, "settingKey");
            Objects.requireNonNull(settingVal, "settingVal");

            settingsCache.computeIfAbsent(plotId, k -> new ConcurrentHashMap<>())
                    .put(settingKey, settingVal);

            return CompletableFuture.runAsync(() -> {
                final String sql = """
                        INSERT INTO fp_settings (plot_id, setting_key, setting_val)
                        VALUES (?, ?, ?)
                        ON CONFLICT(plot_id, setting_key) DO UPDATE SET setting_val = excluded.setting_val
                        """;
                try (Connection conn = getConnection();
                     PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, plotId);
                    ps.setString(2, settingKey);
                    ps.setString(3, settingVal);
                    ps.executeUpdate();
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.SEVERE, "saveSetting failed for " + plotId + "/" + settingKey, e);
                }
            });
        }

        /**
         * Asynchronously loads all settings for a plot into the cache and returns them.
         *
         * @param plotId FK to {@code fp_plots}
         * @return a future resolving to an unmodifiable view of the settings map
         */
        public CompletableFuture<Map<String, String>> getSettings(String plotId) {
            Objects.requireNonNull(plotId, "plotId");

            ConcurrentHashMap<String, String> cached = settingsCache.get(plotId);
            if (cached != null) {
                return CompletableFuture.completedFuture(Collections.unmodifiableMap(cached));
            }

            return CompletableFuture.supplyAsync(() -> {
                final String sql = "SELECT setting_key, setting_val FROM fp_settings WHERE plot_id = ?";
                ConcurrentHashMap<String, String> map = new ConcurrentHashMap<>();
                try (Connection conn = getConnection();
                     PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, plotId);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            map.put(rs.getString("setting_key"), rs.getString("setting_val"));
                        }
                    }
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.SEVERE, "getSettings failed for " + plotId, e);
                }
                settingsCache.put(plotId, map);
                return Collections.unmodifiableMap(map);
            });
        }

        // ── fp_analytics CRUD ────────────────────────────────────────────────

        /**
         * Asynchronously increments the blocks-placed counter and sets the active-gens counter for a plot.
         * {@code blocksPlacedDelta} is added to the existing value; pass 0 to leave it unchanged.
         * {@code gensActiveValue} replaces the existing counter with the current absolute count.
         *
         * @param plotId           FK to {@code fp_plots}
         * @param blocksPlacedDelta amount to add to {@code total_blocks_placed} (≥ 0)
         * @param gensActiveValue   absolute current value for {@code total_gens_active}
         * @return a future that completes when the write is done
         */
        public CompletableFuture<Void> updateAnalytics(String plotId, int blocksPlacedDelta, int gensActiveValue) {
            Objects.requireNonNull(plotId, "plotId");

            // Optimistically update cache
            analyticsCache.compute(plotId, (k, existing) -> {
                int currentBlocks = existing == null ? 0 : existing.totalBlocksPlaced();
                return new PlotAnalytics(plotId, currentBlocks + blocksPlacedDelta, gensActiveValue);
            });

            return CompletableFuture.runAsync(() -> {
                final String sql = """
                        INSERT INTO fp_analytics (plot_id, total_blocks_placed, total_gens_active)
                        VALUES (?, ?, ?)
                        ON CONFLICT(plot_id) DO UPDATE SET
                            total_blocks_placed = total_blocks_placed + excluded.total_blocks_placed,
                            total_gens_active   = excluded.total_gens_active
                        """;
                try (Connection conn = getConnection();
                     PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, plotId);
                    ps.setInt(2, blocksPlacedDelta);
                    ps.setInt(3, gensActiveValue);
                    ps.executeUpdate();
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.SEVERE, "updateAnalytics failed for " + plotId, e);
                }
            });
        }

        /**
         * Asynchronously retrieves analytics for a plot.
         *
         * @param plotId FK to {@code fp_plots}
         * @return a future resolving to an {@link Optional} containing the analytics, or empty
         */
        public CompletableFuture<Optional<PlotAnalytics>> getAnalytics(String plotId) {
            Objects.requireNonNull(plotId, "plotId");

            PlotAnalytics cached = analyticsCache.get(plotId);
            if (cached != null) {
                return CompletableFuture.completedFuture(Optional.of(cached));
            }

            return CompletableFuture.supplyAsync(() -> {
                final String sql = "SELECT plot_id, total_blocks_placed, total_gens_active "
                        + "FROM fp_analytics WHERE plot_id = ?";
                try (Connection conn = getConnection();
                     PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, plotId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            PlotAnalytics analytics = new PlotAnalytics(
                                    rs.getString("plot_id"),
                                    rs.getInt("total_blocks_placed"),
                                    rs.getInt("total_gens_active")
                            );
                            analyticsCache.put(plotId, analytics);
                            return Optional.of(analytics);
                        }
                    }
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.SEVERE, "getAnalytics failed for " + plotId, e);
                }
                return Optional.empty();
            });
        }

        // ── Cache helpers ─────────────────────────────────────────────────────

        /**
         * Returns a live read-only view of the plot cache.
         * Useful for iteration over all loaded plots without hitting the database.
         */
        public Map<String, PlotData> getPlotCacheView() {
            return Collections.unmodifiableMap(plotCache);
        }

        /** Evicts all entries from every in-memory cache. */
        public void invalidateAllCaches() {
            plotCache.clear();
            trustCache.clear();
            settingsCache.clear();
            analyticsCache.clear();
            vaultCache.clear();
        }

        /** Evicts all cached data for a single plot. */
        public void invalidatePlotCache(String plotId) {
            plotCache.remove(plotId);
            trustCache.remove(plotId);
            settingsCache.remove(plotId);
            analyticsCache.remove(plotId);
        }

        /**
         * Returns the cached trust list for a plot without hitting the database.
         * Returns {@code null} if the plot's trust data has not been loaded yet.
         *
         * @param plotId the plot's primary key
         * @return the immutable cached trust list, or {@code null} if not cached
         */
        public @Nullable List<TrustEntry> getCachedTrust(String plotId) {
            return trustCache.get(plotId);
        }

        // ── Row mapping ───────────────────────────────────────────────────────

        private static PlotData rowToPlotData(ResultSet rs) throws SQLException {
            return new PlotData(
                    rs.getString("plot_id"),
                    rs.getString("owner_uuid"),
                    rs.getString("owner_name"),
                    rs.getString("world_name"),
                    rs.getLong("created_at"),
                    rs.getLong("last_seen"),
                    rs.getInt("plot_size")
            );
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3.  PLACEHOLDERAPI EXPANSION
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Registers FarmerPlots variables with PlaceholderAPI.
     *
     * <h3>Available placeholders</h3>
     * <pre>
     * %farmerplots_plot_count%   – number of plots owned by the player (cached)
     * %farmerplots_trust_level_<plotId>% – trust level the player has on that plot
     * </pre>
     */
    private static final class FarmerPlotsExpansion extends PlaceholderExpansion {

        private final FarmerPlotsPlugin plugin;

        FarmerPlotsExpansion(FarmerPlotsPlugin plugin) {
            this.plugin = plugin;
        }

        @Override
        public @NotNull String getIdentifier() {
            return "farmerplots";
        }

        @Override
        public @NotNull String getAuthor() {
            return String.join(", ", plugin.getDescription().getAuthors());
        }

        @Override
        public @NotNull String getVersion() {
            return plugin.getDescription().getVersion();
        }

        @Override
        public boolean persist() {
            return true;
        }

        @Override
        public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
            if (player == null) return null;

            String uuid = player.getUniqueId().toString();

            if (params.equals("plot_count")) {
                // Count how many plots this player owns (uses the in-memory cache when warm)
                long count = plugin.databaseManager.getPlotCacheView().values().stream()
                        .filter(p -> p.ownerUuid().equals(uuid))
                        .count();
                return String.valueOf(count);
            }

            if (params.startsWith("trust_level_")) {
                String plotId = params.substring("trust_level_".length());
                List<TrustEntry> trust = plugin.databaseManager.getCachedTrust(plotId);
                if (trust == null) return "0";
                return trust.stream()
                        .filter(e -> e.guestUuid().equals(uuid))
                        .map(e -> String.valueOf(e.trustLevel()))
                        .findFirst()
                        .orElse("0");
            }

            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4.  PLUGIN LIFECYCLE
    // ─────────────────────────────────────────────────────────────────────────

    private DatabaseManager databaseManager;

    @Override
    public void onEnable() {
        // ── Database ─────────────────────────────────────────────────────────
        databaseManager = new DatabaseManager(this);
        databaseManager.init();

        // ── Purge stale vault entries once on startup ─────────────────────
        databaseManager.purgeExpiredVaultEntries()
                .exceptionally(ex -> {
                    getLogger().log(Level.WARNING, "Vault purge encountered an error", ex);
                    return null;
                });

        // ── PlaceholderAPI ────────────────────────────────────────────────
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new FarmerPlotsExpansion(this).register();
            getLogger().info("PlaceholderAPI expansion registered.");
        }

        getLogger().info("FarmerPlots Centurion Engine enabled.");
    }

    @Override
    public void onDisable() {
        if (databaseManager != null) {
            databaseManager.shutdown();
        }
        getLogger().info("FarmerPlots Centurion Engine disabled.");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5.  COMMANDS
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public boolean onCommand(@NotNull CommandSender sender,
                             @NotNull Command command,
                             @NotNull String label,
                             @NotNull String @NotNull [] args) {
        if (!command.getName().equalsIgnoreCase("farmerplots")) return false;

        if (args.length == 0) {
            sender.sendMessage("§6FarmerPlots §7v" + getDescription().getVersion());
            sender.sendMessage("§7Usage: §f/" + label + " reload");
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("farmerplots.admin")) {
                sender.sendMessage("§cYou don't have permission to do that.");
                return true;
            }
            databaseManager.invalidateAllCaches();
            sender.sendMessage("§aFarmerPlots caches cleared.");
            return true;
        }

        return false;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 6.  PUBLIC API ACCESSORS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the plugin's {@link DatabaseManager} instance.
     * Other plugins (or future modules) can call this to interact with the data layer.
     */
    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    /**
     * Convenience factory: generates a deterministic plot ID from world + chunk coords.
     *
     * @param worldName Bukkit world name
     * @param chunkX    chunk X coordinate
     * @param chunkZ    chunk Z coordinate
     * @return e.g. {@code "world_12_-3"}
     */
    public static String buildPlotId(String worldName, int chunkX, int chunkZ) {
        return worldName + "_" + chunkX + "_" + chunkZ;
    }

    /**
     * Convenience factory: creates a new {@link PlotData} with {@code createdAt} and
     * {@code lastSeen} set to the current system time.
     *
     * @param plotId    the plot's primary key
     * @param owner     the owning player's {@link UUID}
     * @param ownerName the owning player's current display name
     * @param worldName Bukkit world name
     * @param plotSize  edge length in blocks
     * @return a ready-to-persist {@link PlotData}
     */
    public static PlotData createNewPlot(String plotId, UUID owner, String ownerName,
                                         String worldName, int plotSize) {
        long now = System.currentTimeMillis();
        return new PlotData(plotId, owner.toString(), ownerName, worldName, now, now, plotSize);
    }
}
