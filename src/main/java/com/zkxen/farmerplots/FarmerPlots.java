package com.zkxen.farmerplots;

import com.onarandombox.multiversecore.MultiverseCore;
import com.onarandombox.multiversecore.api.MVWorldManager;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;

/**
 * FarmerPlots - PROJECT OVERLORD: Enterprise-Grade Plot Management System
 * Package: com.zkxen.farmerplots
 * Author: zkxen
 */
public class FarmerPlots extends JavaPlugin implements Listener {

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------
    private static final int ROAD_WIDTH = 4;
    private static final long PURGE_INTERVAL_TICKS = 20L * 60 * 60 * 12; // 12 hours
    private static final long VAULT_CLEANUP_INTERVAL_TICKS = 20L * 60 * 60; // 1 hour

    // -------------------------------------------------------------------------
    // Singleton
    // -------------------------------------------------------------------------
    private static FarmerPlots instance;

    public static FarmerPlots getInstance() {
        return instance;
    }

    // -------------------------------------------------------------------------
    // Core subsystems
    // -------------------------------------------------------------------------
    private DatabaseManager databaseManager;
    private GuiManager guiManager;
    private MultiverseCore multiverseCore;

    // -------------------------------------------------------------------------
    // Nuke protocol state (3-stage, 15-second timeout between stages)
    // -------------------------------------------------------------------------
    private int nukeStage = 0;
    private long nukeLastStageTime = 0L;
    private static final long NUKE_TIMEOUT_MS = 15_000L;

    // -------------------------------------------------------------------------
    // Warmup teleport tasks (UUID -> taskId)
    // -------------------------------------------------------------------------
    private final Map<UUID, Integer> warmupTasks = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        // Database
        databaseManager = new DatabaseManager(this);
        databaseManager.init();

        // GUI manager
        guiManager = new GuiManager(this);

        // Multiverse
        if (getServer().getPluginManager().getPlugin("Multiverse-Core") instanceof MultiverseCore mv) {
            multiverseCore = mv;
        } else {
            getLogger().severe("Multiverse-Core not found! Admin nuke commands will be unavailable.");
        }

        // PlaceholderAPI
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new FarmerPlotsExpansion(this).register();
            getLogger().info("PlaceholderAPI expansion registered.");
        }

        // Register events
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(guiManager, this);

        // Register commands
        Objects.requireNonNull(getCommand("plot")).setExecutor(new PlotCommand(this));
        Objects.requireNonNull(getCommand("leaderboard")).setExecutor(new LeaderboardCommand(this));
        Objects.requireNonNull(getCommand("plotsadmin")).setExecutor(new AdminCommand(this));

        // Background tasks
        startPurgeTask();
        startVaultCleanupTask();

        getLogger().info("FarmerPlots enabled - PROJECT OVERLORD v" + getDescription().getVersion());
    }

    @Override
    public void onDisable() {
        warmupTasks.values().forEach(Bukkit.getScheduler()::cancelTask);
        warmupTasks.clear();
        if (databaseManager != null) {
            databaseManager.close();
        }
        getLogger().info("FarmerPlots disabled.");
    }

    // -------------------------------------------------------------------------
    // Background tasks
    // -------------------------------------------------------------------------

    private void startPurgeTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                runPurge();
            }
        }.runTaskTimerAsynchronously(this, PURGE_INTERVAL_TICKS, PURGE_INTERVAL_TICKS);
    }

    private void startVaultCleanupTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                databaseManager.purgeExpiredVaultEntries();
            }
        }.runTaskTimerAsynchronously(this, VAULT_CLEANUP_INTERVAL_TICKS, VAULT_CLEANUP_INTERVAL_TICKS);
    }

    private void runPurge() {
        long purgeDays = getConfig().getLong("purge-days", 14);
        long cutoff = System.currentTimeMillis() - purgeDays * 24 * 60 * 60 * 1000L;
        List<PlotData> stale = databaseManager.getPlotsLastSeenBefore(cutoff);
        for (PlotData plot : stale) {
            // Notify owner if online
            Player owner = Bukkit.getPlayer(UUID.fromString(plot.ownerUuid));
            if (owner != null) {
                owner.sendMessage("§c§lFarmerPlots §8» §7Your plot §e" + plot.plotId
                        + " §7has been purged due to 14 days of inactivity.");
            }
            databaseManager.deletePlot(plot.plotId);
            getLogger().info("[Purge] Removed stale plot: " + plot.plotId);
        }
    }

    // -------------------------------------------------------------------------
    // Protection events
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();
        if (!isPlotWorld(block.getWorld())) return;
        PlotPosition pos = getPlotPosition(block.getLocation());
        if (pos == null) return;
        if (pos.inRoad) {
            event.setCancelled(true);
            return;
        }
        if (!canBuild(player, pos)) {
            event.setCancelled(true);
            player.sendMessage("§c§lFarmerPlots §8» §7You don't have permission to build here.");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();
        if (!isPlotWorld(block.getWorld())) return;
        PlotPosition pos = getPlotPosition(block.getLocation());
        if (pos == null) return;
        if (pos.inRoad) {
            event.setCancelled(true);
            return;
        }
        if (!canBuild(player, pos)) {
            event.setCancelled(true);
            player.sendMessage("§c§lFarmerPlots §8» §7You don't have permission to break blocks here.");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null) return;
        Player player = event.getPlayer();
        Block block = event.getClickedBlock();
        if (!isPlotWorld(block.getWorld())) return;
        PlotPosition pos = getPlotPosition(block.getLocation());
        if (pos == null) return;
        if (pos.inRoad) {
            event.setCancelled(true);
            return;
        }
        if (block.getState() instanceof Container) {
            if (!canOpenContainers(player, pos)) {
                event.setCancelled(true);
                player.sendMessage("§c§lFarmerPlots §8» §7You can't open containers here.");
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!event.hasChangedBlock()) return;
        Player player = event.getPlayer();
        Location to = event.getTo();
        if (to == null || !isPlotWorld(to.getWorld())) return;
        PlotPosition pos = getPlotPosition(to);
        if (pos == null) return;

        String hud;
        if (pos.inRoad) {
            hud = "§e§lROAD §7| §fSafety Zone";
        } else {
            PlotData plot = databaseManager.getPlotAt(pos.worldName, pos.plotIdX, pos.plotIdZ);
            if (plot == null) {
                hud = "§e§lUNCLAIMED §7| §f/plot claim";
            } else {
                hud = "§a§lPLOT: §f" + plot.ownerName + " §7(§e/plot info§7)";
            }
        }
        player.sendActionBar(net.kyori.adventure.text.Component.text(hud));
    }

    // -------------------------------------------------------------------------
    // Permission helpers
    // -------------------------------------------------------------------------

    private boolean isOp(Player player) {
        return player.isOp();
    }

    private boolean canBuild(Player player, PlotPosition pos) {
        if (isOp(player)) return true;
        PlotData plot = databaseManager.getPlotAt(pos.worldName, pos.plotIdX, pos.plotIdZ);
        if (plot == null) return false;
        if (plot.ownerUuid.equals(player.getUniqueId().toString())) return true;
        TrustEntry trust = databaseManager.getTrust(plot.plotId, player.getUniqueId().toString());
        return trust != null && trust.trustLevel >= TrustLevel.WORKER.ordinal();
    }

    private boolean canOpenContainers(Player player, PlotPosition pos) {
        if (isOp(player)) return true;
        PlotData plot = databaseManager.getPlotAt(pos.worldName, pos.plotIdX, pos.plotIdZ);
        if (plot == null) return false;
        if (plot.ownerUuid.equals(player.getUniqueId().toString())) return true;
        TrustEntry trust = databaseManager.getTrust(plot.plotId, player.getUniqueId().toString());
        return trust != null && trust.trustLevel >= TrustLevel.BUILDER.ordinal();
    }

    // -------------------------------------------------------------------------
    // Geometry helpers
    // -------------------------------------------------------------------------

    private boolean isPlotWorld(World world) {
        if (world == null) return false;
        String name = world.getName();
        return name.equals(getConfig().getString("worlds.main", "plots_main"))
                || name.equals(getConfig().getString("worlds.tier3", "plots_t3"))
                || name.equals(getConfig().getString("worlds.tier4", "plots_t4"))
                || name.equals(getConfig().getString("worlds.tier5", "plots_t5"));
    }

    private int getPlotSizeForWorld(String worldName) {
        String main = getConfig().getString("worlds.main", "plots_main");
        String t3 = getConfig().getString("worlds.tier3", "plots_t3");
        String t4 = getConfig().getString("worlds.tier4", "plots_t4");
        String t5 = getConfig().getString("worlds.tier5", "plots_t5");
        if (worldName.equals(main)) return getConfig().getInt("plot-sizes.main", 48);
        if (worldName.equals(t3)) return getConfig().getInt("plot-sizes.tier3", 60);
        if (worldName.equals(t4)) return getConfig().getInt("plot-sizes.tier4", 70);
        if (worldName.equals(t5)) return getConfig().getInt("plot-sizes.tier5", 80);
        return 48;
    }

    PlotPosition getPlotPosition(Location loc) {
        if (loc.getWorld() == null) return null;
        String worldName = loc.getWorld().getName();
        int plotSize = getPlotSizeForWorld(worldName);
        int totalSize = plotSize + ROAD_WIDTH;
        int bx = loc.getBlockX();
        int bz = loc.getBlockZ();

        // Handle negative coordinates properly
        int plotIdX = Math.floorDiv(bx, totalSize);
        int plotIdZ = Math.floorDiv(bz, totalSize);
        int relX = Math.floorMod(bx, totalSize);
        int relZ = Math.floorMod(bz, totalSize);
        boolean inRoad = (relX >= plotSize) || (relZ >= plotSize);

        return new PlotPosition(worldName, plotIdX, plotIdZ, inRoad);
    }

    Location getPlotSpawnLocation(World world, int plotIdX, int plotIdZ) {
        int plotSize = getPlotSizeForWorld(world.getName());
        int totalSize = plotSize + ROAD_WIDTH;
        int blockX = plotIdX * totalSize + plotSize / 2;
        int blockZ = plotIdZ * totalSize + plotSize / 2;
        return new Location(world, blockX + 0.5, 64, blockZ + 0.5, 0f, 0f);
    }

    String buildPlotId(String worldName, int plotIdX, int plotIdZ) {
        return worldName + ":" + plotIdX + ":" + plotIdZ;
    }

    // -------------------------------------------------------------------------
    // NBT Serialization helpers
    // -------------------------------------------------------------------------

    String serializeItemStack(ItemStack item) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (BukkitObjectOutputStream boos = new BukkitObjectOutputStream(baos)) {
            boos.writeObject(item);
        }
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    ItemStack deserializeItemStack(String base64) throws IOException, ClassNotFoundException {
        byte[] bytes = Base64.getDecoder().decode(base64);
        ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
        try (BukkitObjectInputStream bois = new BukkitObjectInputStream(bais)) {
            return (ItemStack) bois.readObject();
        }
    }

    String serializeItemArray(ItemStack[] items) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (BukkitObjectOutputStream boos = new BukkitObjectOutputStream(baos)) {
            boos.writeInt(items.length);
            for (ItemStack item : items) {
                boos.writeObject(item);
            }
        }
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    ItemStack[] deserializeItemArray(String base64) throws IOException, ClassNotFoundException {
        byte[] bytes = Base64.getDecoder().decode(base64);
        ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
        try (BukkitObjectInputStream bois = new BukkitObjectInputStream(bais)) {
            int size = bois.readInt();
            ItemStack[] items = new ItemStack[size];
            for (int i = 0; i < size; i++) {
                items[i] = (ItemStack) bois.readObject();
            }
            return items;
        }
    }

    // -------------------------------------------------------------------------
    // Nuke logging
    // -------------------------------------------------------------------------

    void logNukeAttempt(String name) {
        try {
            Path logFile = getDataFolder().toPath().resolve("logs/nuke_history.txt");
            Files.createDirectories(logFile.getParent());
            String entry = "[" + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    + "] Unauthorized nuke attempt by: " + name + System.lineSeparator();
            Files.writeString(logFile, entry, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Could not write nuke log", e);
        }
    }

    void logNukeAction(String action) {
        try {
            Path logFile = getDataFolder().toPath().resolve("logs/nuke_history.txt");
            Files.createDirectories(logFile.getParent());
            String entry = "[" + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    + "] " + action + System.lineSeparator();
            Files.writeString(logFile, entry, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Could not write nuke log", e);
        }
    }

    // -------------------------------------------------------------------------
    // Nuke protocol state
    // -------------------------------------------------------------------------
    int getNukeStage() { return nukeStage; }
    long getNukeLastStageTime() { return nukeLastStageTime; }
    void setNukeStage(int stage) { nukeStage = stage; nukeLastStageTime = System.currentTimeMillis(); }
    void resetNukeStage() { nukeStage = 0; nukeLastStageTime = 0L; }
    MultiverseCore getMultiverseCore() { return multiverseCore; }

    // -------------------------------------------------------------------------
    // Warmup tasks
    // -------------------------------------------------------------------------
    Map<UUID, Integer> getWarmupTasks() { return warmupTasks; }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------
    DatabaseManager getDatabaseManager() { return databaseManager; }
    GuiManager getGuiManager() { return guiManager; }

    // =========================================================================
    // DATA RECORDS
    // =========================================================================

    static class PlotData {
        String plotId;
        String ownerUuid;
        String ownerName;
        String worldName;
        long createdAt;
        long lastSeen;
        int plotSize;
        int plotIdX;
        int plotIdZ;

        PlotData(String plotId, String ownerUuid, String ownerName, String worldName,
                 long createdAt, long lastSeen, int plotSize, int plotIdX, int plotIdZ) {
            this.plotId = plotId;
            this.ownerUuid = ownerUuid;
            this.ownerName = ownerName;
            this.worldName = worldName;
            this.createdAt = createdAt;
            this.lastSeen = lastSeen;
            this.plotSize = plotSize;
            this.plotIdX = plotIdX;
            this.plotIdZ = plotIdZ;
        }
    }

    static class TrustEntry {
        String plotId;
        String guestUuid;
        String guestName;
        int trustLevel;

        TrustEntry(String plotId, String guestUuid, String guestName, int trustLevel) {
            this.plotId = plotId;
            this.guestUuid = guestUuid;
            this.guestName = guestName;
            this.trustLevel = trustLevel;
        }
    }

    static class DeliveryVaultEntry {
        String uuid;
        String itemBlob;
        long expiresAt;

        DeliveryVaultEntry(String uuid, String itemBlob, long expiresAt) {
            this.uuid = uuid;
            this.itemBlob = itemBlob;
            this.expiresAt = expiresAt;
        }
    }

    static class PlotAnalytics {
        String plotId;
        long totalBlocksPlaced;
        int totalGensActive;

        PlotAnalytics(String plotId, long totalBlocksPlaced, int totalGensActive) {
            this.plotId = plotId;
            this.totalBlocksPlaced = totalBlocksPlaced;
            this.totalGensActive = totalGensActive;
        }
    }

    enum TrustLevel {
        GUEST, WORKER, BUILDER, MANAGER, PARTNER;

        static TrustLevel fromOrdinal(int ordinal) {
            TrustLevel[] values = values();
            if (ordinal < 0 || ordinal >= values.length) return GUEST;
            return values[ordinal];
        }
    }

    static class PlotPosition {
        String worldName;
        int plotIdX;
        int plotIdZ;
        boolean inRoad;

        PlotPosition(String worldName, int plotIdX, int plotIdZ, boolean inRoad) {
            this.worldName = worldName;
            this.plotIdX = plotIdX;
            this.plotIdZ = plotIdZ;
            this.inRoad = inRoad;
        }
    }

    // =========================================================================
    // DATABASE MANAGER
    // =========================================================================

    static class DatabaseManager {
        private final FarmerPlots plugin;
        private HikariDataSource dataSource;

        // In-memory caches
        private final Map<String, PlotData> plotCache = new ConcurrentHashMap<>();
        private final Map<String, List<TrustEntry>> trustCache = new ConcurrentHashMap<>();
        private final Map<String, PlotAnalytics> analyticsCache = new ConcurrentHashMap<>();

        DatabaseManager(FarmerPlots plugin) {
            this.plugin = plugin;
        }

        void init() {
            String dbFile = plugin.getConfig().getString("database.file", "plugins/FarmerPlots/plots.db");
            File file = new File(dbFile);
            file.getParentFile().mkdirs();

            HikariConfig config = new HikariConfig();
            config.setDriverClassName("org.sqlite.JDBC");
            config.setJdbcUrl("jdbc:sqlite:" + file.getAbsolutePath());
            config.setMaximumPoolSize(10);
            config.setConnectionTimeout(5000L);
            config.setIdleTimeout(600000L);
            config.setMaxLifetime(1800000L);
            config.setPoolName("FarmerPlots-Pool");
            config.addDataSourceProperty("journal_mode", "WAL");
            config.addDataSourceProperty("synchronous", "NORMAL");

            dataSource = new HikariDataSource(config);
            createTables();
            loadAllPlotsToCache();
        }

        void close() {
            if (dataSource != null && !dataSource.isClosed()) {
                dataSource.close();
            }
        }

        private Connection getConnection() throws SQLException {
            return dataSource.getConnection();
        }

        private void createTables() {
            String[] sqls = {
                "CREATE TABLE IF NOT EXISTS fp_plots ("
                    + "plot_id TEXT PRIMARY KEY, "
                    + "owner_uuid TEXT NOT NULL, "
                    + "owner_name TEXT NOT NULL, "
                    + "world_name TEXT NOT NULL, "
                    + "created_at BIGINT NOT NULL, "
                    + "last_seen BIGINT NOT NULL, "
                    + "plot_size INT NOT NULL, "
                    + "plot_id_x INT NOT NULL, "
                    + "plot_id_z INT NOT NULL"
                    + ")",
                "CREATE INDEX IF NOT EXISTS idx_fp_plots_owner ON fp_plots(owner_uuid)",
                "CREATE TABLE IF NOT EXISTS fp_trust ("
                    + "plot_id TEXT NOT NULL, "
                    + "guest_uuid TEXT NOT NULL, "
                    + "guest_name TEXT NOT NULL, "
                    + "trust_level INT NOT NULL DEFAULT 0, "
                    + "PRIMARY KEY (plot_id, guest_uuid), "
                    + "FOREIGN KEY (plot_id) REFERENCES fp_plots(plot_id) ON DELETE CASCADE"
                    + ")",
                "CREATE TABLE IF NOT EXISTS fp_delivery_vault ("
                    + "uuid TEXT PRIMARY KEY, "
                    + "item_blob LONGTEXT NOT NULL, "
                    + "expires_at BIGINT NOT NULL"
                    + ")",
                "CREATE TABLE IF NOT EXISTS fp_settings ("
                    + "plot_id TEXT NOT NULL, "
                    + "setting_key TEXT NOT NULL, "
                    + "setting_val TEXT NOT NULL, "
                    + "PRIMARY KEY (plot_id, setting_key), "
                    + "FOREIGN KEY (plot_id) REFERENCES fp_plots(plot_id) ON DELETE CASCADE"
                    + ")",
                "CREATE TABLE IF NOT EXISTS fp_analytics ("
                    + "plot_id TEXT PRIMARY KEY, "
                    + "total_blocks_placed BIGINT NOT NULL DEFAULT 0, "
                    + "total_gens_active INT NOT NULL DEFAULT 0, "
                    + "FOREIGN KEY (plot_id) REFERENCES fp_plots(plot_id) ON DELETE CASCADE"
                    + ")"
            };
            try (Connection conn = getConnection()) {
                for (String sql : sqls) {
                    try (Statement stmt = conn.createStatement()) {
                        stmt.execute(sql);
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to create tables", e);
            }
        }

        private void loadAllPlotsToCache() {
            CompletableFuture.runAsync(() -> {
                try (Connection conn = getConnection();
                     Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT * FROM fp_plots")) {
                    while (rs.next()) {
                        PlotData plot = mapPlot(rs);
                        plotCache.put(plot.plotId, plot);
                    }
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.WARNING, "Failed to load plots cache", e);
                }
            });
        }

        private PlotData mapPlot(ResultSet rs) throws SQLException {
            return new PlotData(
                rs.getString("plot_id"),
                rs.getString("owner_uuid"),
                rs.getString("owner_name"),
                rs.getString("world_name"),
                rs.getLong("created_at"),
                rs.getLong("last_seen"),
                rs.getInt("plot_size"),
                rs.getInt("plot_id_x"),
                rs.getInt("plot_id_z")
            );
        }

        // --- Plots ---

        PlotData getPlotAt(String worldName, int plotIdX, int plotIdZ) {
            String key = worldName + ":" + plotIdX + ":" + plotIdZ;
            return plotCache.get(key);
        }

        PlotData getPlotById(String plotId) {
            return plotCache.get(plotId);
        }

        List<PlotData> getPlotsByOwner(String ownerUuid) {
            List<PlotData> result = new ArrayList<>();
            for (PlotData plot : plotCache.values()) {
                if (plot.ownerUuid.equals(ownerUuid)) result.add(plot);
            }
            return result;
        }

        CompletableFuture<Void> savePlot(PlotData plot) {
            plotCache.put(plot.plotId, plot);
            return CompletableFuture.runAsync(() -> {
                String sql = "INSERT OR REPLACE INTO fp_plots "
                        + "(plot_id, owner_uuid, owner_name, world_name, created_at, last_seen, plot_size, plot_id_x, plot_id_z) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
                try (Connection conn = getConnection();
                     PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, plot.plotId);
                    ps.setString(2, plot.ownerUuid);
                    ps.setString(3, plot.ownerName);
                    ps.setString(4, plot.worldName);
                    ps.setLong(5, plot.createdAt);
                    ps.setLong(6, plot.lastSeen);
                    ps.setInt(7, plot.plotSize);
                    ps.setInt(8, plot.plotIdX);
                    ps.setInt(9, plot.plotIdZ);
                    ps.executeUpdate();
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.WARNING, "Failed to save plot " + plot.plotId, e);
                }
            });
        }

        CompletableFuture<Void> updateLastSeen(String plotId) {
            PlotData cached = plotCache.get(plotId);
            if (cached != null) cached.lastSeen = System.currentTimeMillis();
            return CompletableFuture.runAsync(() -> {
                try (Connection conn = getConnection();
                     PreparedStatement ps = conn.prepareStatement(
                             "UPDATE fp_plots SET last_seen=? WHERE plot_id=?")) {
                    ps.setLong(1, System.currentTimeMillis());
                    ps.setString(2, plotId);
                    ps.executeUpdate();
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.WARNING, "Failed to update last_seen for " + plotId, e);
                }
            });
        }

        void deletePlot(String plotId) {
            plotCache.remove(plotId);
            trustCache.remove(plotId);
            analyticsCache.remove(plotId);
            CompletableFuture.runAsync(() -> {
                try (Connection conn = getConnection();
                     PreparedStatement ps = conn.prepareStatement("DELETE FROM fp_plots WHERE plot_id=?")) {
                    ps.setString(1, plotId);
                    ps.executeUpdate();
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.WARNING, "Failed to delete plot " + plotId, e);
                }
            });
        }

        List<PlotData> getPlotsLastSeenBefore(long timestamp) {
            List<PlotData> result = new ArrayList<>();
            for (PlotData plot : plotCache.values()) {
                if (plot.lastSeen < timestamp) result.add(plot);
            }
            return result;
        }

        List<PlotData> getTopPlotsByBlocks(int limit) {
            List<PlotAnalytics> sorted = new ArrayList<>(analyticsCache.values());
            sorted.sort((a, b) -> Long.compare(b.totalBlocksPlaced, a.totalBlocksPlaced));
            List<PlotData> result = new ArrayList<>();
            for (int i = 0; i < Math.min(limit, sorted.size()); i++) {
                PlotData plot = plotCache.get(sorted.get(i).plotId);
                if (plot != null) result.add(plot);
            }
            return result;
        }

        List<PlotData> getTopPlotsByAge(int limit) {
            List<PlotData> sorted = new ArrayList<>(plotCache.values());
            sorted.sort(Comparator.comparingLong(a -> a.createdAt));
            return sorted.subList(0, Math.min(limit, sorted.size()));
        }

        // --- Trust ---

        TrustEntry getTrust(String plotId, String guestUuid) {
            List<TrustEntry> entries = trustCache.computeIfAbsent(plotId, k -> loadTrustSync(k));
            for (TrustEntry e : entries) {
                if (e.guestUuid.equals(guestUuid)) return e;
            }
            return null;
        }

        List<TrustEntry> getTrustedPlayers(String plotId) {
            return trustCache.computeIfAbsent(plotId, k -> loadTrustSync(k));
        }

        private List<TrustEntry> loadTrustSync(String plotId) {
            List<TrustEntry> result = new ArrayList<>();
            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "SELECT * FROM fp_trust WHERE plot_id=?")) {
                ps.setString(1, plotId);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    result.add(new TrustEntry(
                        rs.getString("plot_id"),
                        rs.getString("guest_uuid"),
                        rs.getString("guest_name"),
                        rs.getInt("trust_level")
                    ));
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to load trust for " + plotId, e);
            }
            return result;
        }

        CompletableFuture<Void> setTrust(String plotId, String guestUuid, String guestName, int level) {
            List<TrustEntry> entries = trustCache.computeIfAbsent(plotId, k -> new ArrayList<>());
            entries.removeIf(e -> e.guestUuid.equals(guestUuid));
            entries.add(new TrustEntry(plotId, guestUuid, guestName, level));
            return CompletableFuture.runAsync(() -> {
                String sql = "INSERT OR REPLACE INTO fp_trust (plot_id, guest_uuid, guest_name, trust_level) VALUES (?, ?, ?, ?)";
                try (Connection conn = getConnection();
                     PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, plotId);
                    ps.setString(2, guestUuid);
                    ps.setString(3, guestName);
                    ps.setInt(4, level);
                    ps.executeUpdate();
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.WARNING, "Failed to set trust", e);
                }
            });
        }

        CompletableFuture<Void> removeTrust(String plotId, String guestUuid) {
            List<TrustEntry> entries = trustCache.get(plotId);
            if (entries != null) entries.removeIf(e -> e.guestUuid.equals(guestUuid));
            return CompletableFuture.runAsync(() -> {
                try (Connection conn = getConnection();
                     PreparedStatement ps = conn.prepareStatement(
                             "DELETE FROM fp_trust WHERE plot_id=? AND guest_uuid=?")) {
                    ps.setString(1, plotId);
                    ps.setString(2, guestUuid);
                    ps.executeUpdate();
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.WARNING, "Failed to remove trust", e);
                }
            });
        }

        // --- Delivery Vault ---

        DeliveryVaultEntry getVaultEntry(String playerUuid) {
            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "SELECT * FROM fp_delivery_vault WHERE uuid=? AND expires_at > ?")) {
                ps.setString(1, playerUuid);
                ps.setLong(2, System.currentTimeMillis());
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    return new DeliveryVaultEntry(rs.getString("uuid"), rs.getString("item_blob"), rs.getLong("expires_at"));
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to get vault entry", e);
            }
            return null;
        }

        CompletableFuture<Void> saveVaultEntry(String playerUuid, String itemBlob, long expiresAt) {
            return CompletableFuture.runAsync(() -> {
                String sql = "INSERT OR REPLACE INTO fp_delivery_vault (uuid, item_blob, expires_at) VALUES (?, ?, ?)";
                try (Connection conn = getConnection();
                     PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, playerUuid);
                    ps.setString(2, itemBlob);
                    ps.setLong(3, expiresAt);
                    ps.executeUpdate();
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.WARNING, "Failed to save vault entry", e);
                }
            });
        }

        void deleteVaultEntry(String playerUuid) {
            CompletableFuture.runAsync(() -> {
                try (Connection conn = getConnection();
                     PreparedStatement ps = conn.prepareStatement(
                             "DELETE FROM fp_delivery_vault WHERE uuid=?")) {
                    ps.setString(1, playerUuid);
                    ps.executeUpdate();
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.WARNING, "Failed to delete vault entry", e);
                }
            });
        }

        void purgeExpiredVaultEntries() {
            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "DELETE FROM fp_delivery_vault WHERE expires_at <= ?")) {
                ps.setLong(1, System.currentTimeMillis());
                int deleted = ps.executeUpdate();
                if (deleted > 0) plugin.getLogger().info("[Vault] Purged " + deleted + " expired vault entries.");
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to purge vault entries", e);
            }
        }

        // --- Analytics ---

        PlotAnalytics getAnalytics(String plotId) {
            return analyticsCache.computeIfAbsent(plotId, k -> {
                try (Connection conn = getConnection();
                     PreparedStatement ps = conn.prepareStatement(
                             "SELECT * FROM fp_analytics WHERE plot_id=?")) {
                    ps.setString(1, k);
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) {
                        return new PlotAnalytics(k, rs.getLong("total_blocks_placed"), rs.getInt("total_gens_active"));
                    }
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.WARNING, "Failed to get analytics", e);
                }
                return new PlotAnalytics(k, 0, 0);
            });
        }

        void updateAnalytics(String plotId, long blocksPlacedDelta, int gensActive) {
            PlotAnalytics cached = analyticsCache.computeIfAbsent(plotId,
                    k -> new PlotAnalytics(k, 0, 0));
            cached.totalBlocksPlaced += blocksPlacedDelta;
            cached.totalGensActive = gensActive;
            CompletableFuture.runAsync(() -> {
                String sql = "INSERT INTO fp_analytics (plot_id, total_blocks_placed, total_gens_active) "
                        + "VALUES (?, ?, ?) ON CONFLICT(plot_id) DO UPDATE SET "
                        + "total_blocks_placed = total_blocks_placed + ?, total_gens_active = ?";
                try (Connection conn = getConnection();
                     PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, plotId);
                    ps.setLong(2, blocksPlacedDelta);
                    ps.setInt(3, gensActive);
                    ps.setLong(4, blocksPlacedDelta);
                    ps.setInt(5, gensActive);
                    ps.executeUpdate();
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.WARNING, "Failed to update analytics", e);
                }
            });
        }

        // --- Settings ---

        String getSetting(String plotId, String key, String def) {
            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "SELECT setting_val FROM fp_settings WHERE plot_id=? AND setting_key=?")) {
                ps.setString(1, plotId);
                ps.setString(2, key);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) return rs.getString("setting_val");
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to get setting", e);
            }
            return def;
        }

        void setSetting(String plotId, String key, String value) {
            CompletableFuture.runAsync(() -> {
                String sql = "INSERT OR REPLACE INTO fp_settings (plot_id, setting_key, setting_val) VALUES (?, ?, ?)";
                try (Connection conn = getConnection();
                     PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, plotId);
                    ps.setString(2, key);
                    ps.setString(3, value);
                    ps.executeUpdate();
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.WARNING, "Failed to set setting", e);
                }
            });
        }

        void wipeAllData() throws SQLException {
            try (Connection conn = getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.execute("DELETE FROM fp_analytics");
                stmt.execute("DELETE FROM fp_settings");
                stmt.execute("DELETE FROM fp_trust");
                stmt.execute("DELETE FROM fp_delivery_vault");
                stmt.execute("DELETE FROM fp_plots");
            }
        }

        void invalidateAllCaches() {
            plotCache.clear();
            trustCache.clear();
            analyticsCache.clear();
            loadAllPlotsToCache();
        }

        void invalidatePlotCache(String plotId) {
            plotCache.remove(plotId);
            trustCache.remove(plotId);
            analyticsCache.remove(plotId);
        }

        Map<String, PlotData> getPlotCacheView() {
            return Collections.unmodifiableMap(plotCache);
        }

        List<TrustEntry> getCachedTrust(String plotId) {
            return trustCache.getOrDefault(plotId, Collections.emptyList());
        }

        int countPlotsByOwner(String ownerUuid) {
            int count = 0;
            for (PlotData p : plotCache.values()) {
                if (p.ownerUuid.equals(ownerUuid)) count++;
            }
            return count;
        }
    }

    // =========================================================================
    // GUI MANAGER
    // =========================================================================

    static class GuiManager implements Listener {
        private final FarmerPlots plugin;
        private static final String MAIN_GUI_TITLE = "§8§lFarmerPlots §7- §bMain Menu";
        private static final String TRUST_GUI_TITLE = "§8§lFarmerPlots §7- §aTrust Manager";
        private static final String SETTINGS_GUI_TITLE = "§8§lFarmerPlots §7- §eSettings";
        private static final String VAULT_GUI_TITLE = "§8§lFarmerPlots §7- §6Delivery Vault";
        private static final String LEADERBOARD_GUI_TITLE = "§8§lFarmerPlots §7- §dLeaderboard";

        GuiManager(FarmerPlots plugin) {
            this.plugin = plugin;
        }

        // GUI 1: Main Menu
        void openMainGui(Player player) {
            Inventory inv = Bukkit.createInventory(null, 54, MAIN_GUI_TITLE);
            fillBorder(inv, Material.BLUE_STAINED_GLASS_PANE);

            // Claim Property
            ItemStack claimItem = createItem(Material.GOLDEN_HOE, "§e§lClaim Property",
                    List.of("§7Claim the plot you're standing on.", "§7Cost: §aFree"));
            inv.setItem(13, claimItem);

            // Home Warp
            ItemStack homeItem = createItem(Material.COMPASS, "§b§lHome Warp",
                    List.of("§7Teleport to your plot.", "§7Warmup: §e3 seconds"));
            inv.setItem(20, homeItem);

            // Trust Manager
            ItemStack trustItem = createItem(Material.PLAYER_HEAD, "§a§lTrust Manager",
                    List.of("§7Manage who can build", "§7on your plot."));
            inv.setItem(22, trustItem);

            // Delivery Vault
            ItemStack vaultItem = createItem(Material.ENDER_CHEST, "§6§lDelivery Vault",
                    List.of("§7View items recovered from", "§7your purged plots.",
                            "§7Expires: §c24 hours"));
            inv.setItem(24, vaultItem);

            // Unclaim Plot
            ItemStack unclaimItem = createItem(Material.BARRIER, "§c§lUnclaim Plot",
                    List.of("§7Unclaim the plot you're", "§7standing on.", "§c§lWARNING: Items will be sent", "§c§lto vault!"));
            inv.setItem(40, unclaimItem);

            player.openInventory(inv);
        }

        // GUI 2: Trust Manager
        void openTrustGui(Player player) {
            PlotPosition pos = plugin.getPlotPosition(player.getLocation());
            if (pos == null || pos.inRoad) {
                player.sendMessage("§c§lFarmerPlots §8» §7You must be standing on your plot.");
                return;
            }
            PlotData plot = plugin.databaseManager.getPlotAt(pos.worldName, pos.plotIdX, pos.plotIdZ);
            if (plot == null || !plot.ownerUuid.equals(player.getUniqueId().toString())) {
                player.sendMessage("§c§lFarmerPlots §8» §7You don't own this plot.");
                return;
            }

            Inventory inv = Bukkit.createInventory(null, 54, TRUST_GUI_TITLE);
            fillBorder(inv, Material.GREEN_STAINED_GLASS_PANE);

            List<TrustEntry> trusted = plugin.databaseManager.getTrustedPlayers(plot.plotId);
            int slot = 10;
            for (TrustEntry entry : trusted) {
                if (slot > 43) break;
                TrustLevel lvl = TrustLevel.fromOrdinal(entry.trustLevel);
                ItemStack skull = createSkull(entry.guestName,
                        List.of("§7Level: §e" + lvl.name(),
                                "§a§lLeft-click §7to promote",
                                "§c§lRight-click §7to revoke"));
                inv.setItem(slot, skull);
                slot++;
                if (slot == 17 || slot == 26 || slot == 35) slot += 2;
            }

            // Add Trust button
            ItemStack addBtn = createItem(Material.OAK_SIGN, "§e§lAdd Player",
                    List.of("§7Type the player name in chat", "§7to add them to your plot."));
            inv.setItem(26, addBtn);

            player.openInventory(inv);
        }

        // GUI 3: Settings Panel
        void openSettingsGui(Player player) {
            PlotPosition pos = plugin.getPlotPosition(player.getLocation());
            if (pos == null || pos.inRoad) {
                player.sendMessage("§c§lFarmerPlots §8» §7You must be standing on your plot.");
                return;
            }
            PlotData plot = plugin.databaseManager.getPlotAt(pos.worldName, pos.plotIdX, pos.plotIdZ);
            if (plot == null || !plot.ownerUuid.equals(player.getUniqueId().toString())) {
                player.sendMessage("§c§lFarmerPlots §8» §7You don't own this plot.");
                return;
            }

            Inventory inv = Bukkit.createInventory(null, 54, SETTINGS_GUI_TITLE);
            fillBorder(inv, Material.YELLOW_STAINED_GLASS_PANE);

            boolean pvp = Boolean.parseBoolean(plugin.databaseManager.getSetting(plot.plotId, "pvp", "false"));
            boolean tnt = Boolean.parseBoolean(plugin.databaseManager.getSetting(plot.plotId, "tnt", "false"));
            boolean physics = Boolean.parseBoolean(plugin.databaseManager.getSetting(plot.plotId, "physics", "true"));
            boolean entrySound = Boolean.parseBoolean(plugin.databaseManager.getSetting(plot.plotId, "entry_sound", "true"));

            inv.setItem(11, createToggleItem("§c§lPvP", pvp, List.of("§7Toggle player vs player", "§7combat on your plot.")));
            inv.setItem(13, createToggleItem("§6§lTNT", tnt, List.of("§7Toggle TNT explosions", "§7on your plot.")));
            inv.setItem(15, createToggleItem("§a§lPhysics", physics, List.of("§7Toggle block physics", "§7(sand, gravel, etc.)")));
            inv.setItem(20, createToggleItem("§b§lEntry Sound", entrySound, List.of("§7Play a sound when", "§7players enter your plot.")));

            // Info
            ItemStack infoItem = createItem(Material.PAPER, "§f§lPlot Info",
                    List.of(
                        "§7ID: §e" + plot.plotId,
                        "§7Size: §e" + plot.plotSize + "x" + plot.plotSize,
                        "§7Created: §e" + formatDate(plot.createdAt),
                        "§7World: §e" + plot.worldName
                    ));
            inv.setItem(31, infoItem);

            player.openInventory(inv);
        }

        // GUI 4: Delivery Vault
        void openVaultGui(Player player) {
            Inventory inv = Bukkit.createInventory(null, 54, VAULT_GUI_TITLE);
            fillBorder(inv, Material.ORANGE_STAINED_GLASS_PANE);

            DeliveryVaultEntry entry = plugin.databaseManager.getVaultEntry(player.getUniqueId().toString());
            if (entry == null) {
                ItemStack empty = createItem(Material.GRAY_STAINED_GLASS_PANE, "§7§lEmpty Vault",
                        List.of("§7No items in your vault.", "§7Items are deposited when", "§7your plot is purged."));
                inv.setItem(22, empty);
            } else {
                long remaining = entry.expiresAt - System.currentTimeMillis();
                long hours = remaining / 3_600_000L;
                long minutes = (remaining % 3_600_000L) / 60_000L;

                try {
                    ItemStack[] items = plugin.deserializeItemArray(entry.itemBlob);
                    int slot = 10;
                    for (ItemStack item : items) {
                        if (item != null && slot < 44) {
                            inv.setItem(slot, item.clone());
                            slot++;
                            if (slot == 17 || slot == 26 || slot == 35) slot += 2;
                        }
                    }
                } catch (IOException | ClassNotFoundException e) {
                    plugin.getLogger().log(Level.WARNING, "Failed to deserialize vault items", e);
                }

                ItemStack timerItem = createItem(Material.CLOCK, "§e§lVault Timer",
                        List.of("§7Expires in: §c" + hours + "h " + minutes + "m",
                                "§7Click items to collect them."));
                inv.setItem(49, timerItem);
            }

            player.openInventory(inv);
        }

        // GUI 5: Leaderboard
        void openLeaderboardGui(Player player, String type) {
            Inventory inv = Bukkit.createInventory(null, 54, LEADERBOARD_GUI_TITLE);
            fillBorder(inv, Material.PURPLE_STAINED_GLASS_PANE);

            List<PlotData> topPlots;
            String typeLabel;
            switch (type.toLowerCase()) {
                case "age" -> {
                    topPlots = plugin.databaseManager.getTopPlotsByAge(10);
                    typeLabel = "§d§lOldest Plots";
                }
                default -> {
                    topPlots = plugin.databaseManager.getTopPlotsByBlocks(10);
                    typeLabel = "§d§lTop Builders";
                }
            }

            ItemStack headerItem = createItem(Material.NETHER_STAR, typeLabel,
                    List.of("§7Showing top " + topPlots.size() + " plots"));
            inv.setItem(4, headerItem);

            int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21};
            for (int i = 0; i < Math.min(topPlots.size(), slots.length); i++) {
                PlotData plot = topPlots.get(i);
                PlotAnalytics analytics = plugin.databaseManager.getAnalytics(plot.plotId);
                ItemStack skull = createSkull(plot.ownerName,
                        List.of(
                            "§7#" + (i + 1) + " §e" + plot.ownerName,
                            "§7Plot: §f" + plot.plotId,
                            "§7Blocks: §a" + analytics.totalBlocksPlaced,
                            "§7Created: §e" + formatDate(plot.createdAt)
                        ));
                inv.setItem(slots[i], skull);
            }

            player.openInventory(inv);
        }

        @EventHandler
        public void onInventoryClick(InventoryClickEvent event) {
            if (!(event.getWhoClicked() instanceof Player player)) return;
            String title = event.getView().getTitle();
            if (!title.startsWith("§8§lFarmerPlots")) return;
            event.setCancelled(true);

            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || clicked.getType() == Material.AIR) return;
            if (clicked.getItemMeta() == null) return;

            String displayName = clicked.getItemMeta().getDisplayName();
            if (displayName == null) return;

            if (title.equals(MAIN_GUI_TITLE)) {
                handleMainGuiClick(player, displayName);
            } else if (title.equals(TRUST_GUI_TITLE)) {
                handleTrustGuiClick(player, event, clicked, displayName);
            } else if (title.equals(SETTINGS_GUI_TITLE)) {
                handleSettingsGuiClick(player, displayName);
            } else if (title.equals(VAULT_GUI_TITLE)) {
                handleVaultGuiClick(player, event, clicked);
            }
        }

        private void handleMainGuiClick(Player player, String name) {
            player.closeInventory();
            if (name.contains("Claim Property")) {
                new PlotCommand(plugin).claimPlot(player);
            } else if (name.contains("Home Warp")) {
                new PlotCommand(plugin).homeTeleport(player);
            } else if (name.contains("Trust Manager")) {
                Bukkit.getScheduler().runTask(plugin, () -> openTrustGui(player));
            } else if (name.contains("Delivery Vault")) {
                Bukkit.getScheduler().runTask(plugin, () -> openVaultGui(player));
            } else if (name.contains("Unclaim Plot")) {
                new PlotCommand(plugin).unclaimPlot(player);
            }
        }

        private void handleTrustGuiClick(Player player, InventoryClickEvent event, ItemStack clicked, String name) {
            PlotPosition pos = plugin.getPlotPosition(player.getLocation());
            if (pos == null || pos.inRoad) return;
            PlotData plot = plugin.databaseManager.getPlotAt(pos.worldName, pos.plotIdX, pos.plotIdZ);
            if (plot == null || !plot.ownerUuid.equals(player.getUniqueId().toString())) return;

            if (name.contains("Add Player")) {
                player.closeInventory();
                player.sendMessage("§e§lFarmerPlots §8» §7Type the player name to trust (or §ccancel§7):");
                // In a full implementation, a chat listener would capture the next message
            } else if (clicked.getType() == Material.PLAYER_HEAD) {
                if (clicked.getItemMeta() instanceof SkullMeta skullMeta) {
                    String targetName = skullMeta.getOwningPlayer() != null
                            ? skullMeta.getOwningPlayer().getName() : null;
                    if (targetName == null) return;
                    Player target = Bukkit.getPlayer(targetName);
                    if (target == null) return;
                    String targetUuid = target.getUniqueId().toString();
                    TrustEntry existing = plugin.databaseManager.getTrust(plot.plotId, targetUuid);
                    int currentLevel = existing != null ? existing.trustLevel : 0;
                    if (event.getClick().isRightClick()) {
                        plugin.databaseManager.removeTrust(plot.plotId, targetUuid);
                        player.sendMessage("§c§lFarmerPlots §8» §7Revoked trust for §e" + targetName);
                        Bukkit.getScheduler().runTask(plugin, () -> openTrustGui(player));
                    } else {
                        int newLevel = Math.min(currentLevel + 1, TrustLevel.values().length - 1);
                        plugin.databaseManager.setTrust(plot.plotId, targetUuid, targetName, newLevel);
                        player.sendMessage("§a§lFarmerPlots §8» §7Promoted §e" + targetName
                                + " §7to §a" + TrustLevel.fromOrdinal(newLevel).name());
                        Bukkit.getScheduler().runTask(plugin, () -> openTrustGui(player));
                    }
                }
            }
        }

        private void handleSettingsGuiClick(Player player, String name) {
            PlotPosition pos = plugin.getPlotPosition(player.getLocation());
            if (pos == null || pos.inRoad) return;
            PlotData plot = plugin.databaseManager.getPlotAt(pos.worldName, pos.plotIdX, pos.plotIdZ);
            if (plot == null || !plot.ownerUuid.equals(player.getUniqueId().toString())) return;

            String setting = null;
            if (name.contains("PvP")) setting = "pvp";
            else if (name.contains("TNT")) setting = "tnt";
            else if (name.contains("Physics")) setting = "physics";
            else if (name.contains("Entry Sound")) setting = "entry_sound";

            if (setting != null) {
                String current = plugin.databaseManager.getSetting(plot.plotId, setting, "false");
                boolean newVal = !Boolean.parseBoolean(current);
                plugin.databaseManager.setSetting(plot.plotId, setting, String.valueOf(newVal));
                player.sendMessage("§e§lFarmerPlots §8» §7Setting §e" + setting + " §7set to §a" + newVal);
                Bukkit.getScheduler().runTask(plugin, () -> openSettingsGui(player));
            }
        }

        private void handleVaultGuiClick(Player player, InventoryClickEvent event, ItemStack clicked) {
            if (clicked.getType() == Material.CLOCK || clicked.getType() == Material.GRAY_STAINED_GLASS_PANE) return;
            // Collect item to inventory
            HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(clicked.clone());
            if (leftover.isEmpty()) {
                event.getInventory().setItem(event.getSlot(), new ItemStack(Material.AIR));
                player.sendMessage("§a§lFarmerPlots §8» §7Item collected!");
                // Check if vault is now empty
                boolean hasItems = false;
                for (ItemStack item : event.getInventory().getContents()) {
                    if (item != null && item.getType() != Material.AIR
                            && item.getType() != Material.ORANGE_STAINED_GLASS_PANE
                            && item.getType() != Material.CLOCK) {
                        hasItems = true;
                        break;
                    }
                }
                if (!hasItems) {
                    plugin.databaseManager.deleteVaultEntry(player.getUniqueId().toString());
                }
            } else {
                player.sendMessage("§c§lFarmerPlots §8» §7Your inventory is full!");
            }
        }

        // -------------------------------------------------------------------------
        // Item creation helpers
        // -------------------------------------------------------------------------

        private ItemStack createItem(Material material, String displayName, List<String> lore) {
            ItemStack item = new ItemStack(material);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(displayName);
                meta.setLore(lore);
                item.setItemMeta(meta);
            }
            return item;
        }

        @SuppressWarnings("deprecation")
        private ItemStack createSkull(String playerName, List<String> lore) {
            ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) skull.getItemMeta();
            if (meta != null) {
                Player online = Bukkit.getPlayerExact(playerName);
                if (online != null) {
                    meta.setOwningPlayer(online);
                } else {
                    meta.setOwningPlayer(Bukkit.getOfflinePlayer(playerName));
                }
                meta.setDisplayName("§e" + playerName);
                meta.setLore(lore);
                skull.setItemMeta(meta);
            }
            return skull;
        }

        private ItemStack createToggleItem(String label, boolean enabled, List<String> description) {
            Material mat = enabled ? Material.LIME_DYE : Material.GRAY_DYE;
            String status = enabled ? "§aEnabled" : "§cDisabled";
            List<String> lore = new ArrayList<>(description);
            lore.add("");
            lore.add("§7Status: " + status);
            lore.add("§e§lClick §7to toggle");
            return createItem(mat, label, lore);
        }

        private void fillBorder(Inventory inv, Material material) {
            ItemStack pane = createItem(material, " ", Collections.emptyList());
            for (int i = 0; i < 9; i++) inv.setItem(i, pane);
            for (int i = 45; i < 54; i++) inv.setItem(i, pane);
            for (int i = 1; i <= 4; i++) {
                inv.setItem(i * 9, pane);
                inv.setItem(i * 9 + 8, pane);
            }
        }

        private static String formatDate(long timestamp) {
            LocalDateTime dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault());
            return dt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        }
    }

    // =========================================================================
    // PLOT COMMAND
    // =========================================================================

    static class PlotCommand implements CommandExecutor {
        private final FarmerPlots plugin;

        PlotCommand(FarmerPlots plugin) {
            this.plugin = plugin;
        }

        @Override
        public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                                 @NotNull String label, @NotNull String[] args) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cThis command can only be run by a player.");
                return true;
            }
            if (args.length == 0 || args[0].equalsIgnoreCase("gui")) {
                plugin.guiManager.openMainGui(player);
                return true;
            }
            switch (args[0].toLowerCase()) {
                case "help" -> sendHelp(player);
                case "claim" -> claimPlot(player);
                case "unclaim" -> unclaimPlot(player);
                case "home" -> homeTeleport(player);
                case "info" -> plotInfo(player);
                case "trust" -> {
                    if (args.length < 2) {
                        player.sendMessage("§cUsage: /plot trust <player> [level]");
                        return true;
                    }
                    int level = args.length >= 3 ? parseTrustLevel(args[2]) : TrustLevel.WORKER.ordinal();
                    trustPlayer(player, args[1], level);
                }
                case "untrust" -> {
                    if (args.length < 2) {
                        player.sendMessage("§cUsage: /plot untrust <player>");
                        return true;
                    }
                    untrustPlayer(player, args[1]);
                }
                default -> sendHelp(player);
            }
            return true;
        }

        private void sendHelp(Player player) {
            player.sendMessage("§8§m----------§r §e§lFarmerPlots §8§m----------");
            player.sendMessage("§e/plot gui §8» §7Open the main GUI");
            player.sendMessage("§e/plot claim §8» §7Claim the plot you're on");
            player.sendMessage("§e/plot unclaim §8» §7Unclaim your current plot");
            player.sendMessage("§e/plot home §8» §7Teleport to your plot");
            player.sendMessage("§e/plot info §8» §7View info about the current plot");
            player.sendMessage("§e/plot trust <player> [level] §8» §7Trust a player");
            player.sendMessage("§e/plot untrust <player> §8» §7Remove trust");
            player.sendMessage("§8§m-------------------------------------");
        }

        void claimPlot(Player player) {
            Location loc = player.getLocation();
            if (!plugin.isPlotWorld(loc.getWorld())) {
                player.sendMessage("§c§lFarmerPlots §8» §7You must be in a plot world to claim.");
                return;
            }
            PlotPosition pos = plugin.getPlotPosition(loc);
            if (pos == null || pos.inRoad) {
                player.sendMessage("§c§lFarmerPlots §8» §7You can't claim a road.");
                return;
            }
            PlotData existing = plugin.databaseManager.getPlotAt(pos.worldName, pos.plotIdX, pos.plotIdZ);
            if (existing != null) {
                if (existing.ownerUuid.equals(player.getUniqueId().toString())) {
                    player.sendMessage("§c§lFarmerPlots §8» §7You already own this plot.");
                } else {
                    player.sendMessage("§c§lFarmerPlots §8» §7This plot is already claimed by §e" + existing.ownerName);
                }
                return;
            }
            String plotId = plugin.buildPlotId(pos.worldName, pos.plotIdX, pos.plotIdZ);
            int plotSize = plugin.getPlotSizeForWorld(pos.worldName);
            long now = System.currentTimeMillis();
            PlotData newPlot = new PlotData(plotId, player.getUniqueId().toString(), player.getName(),
                    pos.worldName, now, now, plotSize, pos.plotIdX, pos.plotIdZ);
            plugin.databaseManager.savePlot(newPlot).thenRun(() ->
                plugin.databaseManager.updateAnalytics(plotId, 0, 0)
            );
            player.sendMessage("§a§lFarmerPlots §8» §7Successfully claimed plot §e" + plotId + "§7!");
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
        }

        void unclaimPlot(Player player) {
            Location loc = player.getLocation();
            if (!plugin.isPlotWorld(loc.getWorld())) {
                player.sendMessage("§c§lFarmerPlots §8» §7You must be in a plot world.");
                return;
            }
            PlotPosition pos = plugin.getPlotPosition(loc);
            if (pos == null || pos.inRoad) {
                player.sendMessage("§c§lFarmerPlots §8» §7You must stand on a plot.");
                return;
            }
            PlotData plot = plugin.databaseManager.getPlotAt(pos.worldName, pos.plotIdX, pos.plotIdZ);
            if (plot == null) {
                player.sendMessage("§c§lFarmerPlots §8» §7This plot is not claimed.");
                return;
            }
            if (!plot.ownerUuid.equals(player.getUniqueId().toString()) && !player.isOp()) {
                player.sendMessage("§c§lFarmerPlots §8» §7You don't own this plot.");
                return;
            }
            // Collect container items and save to vault
            collectAndSaveToVault(player, plot);
            plugin.databaseManager.deletePlot(plot.plotId);
            player.sendMessage("§a§lFarmerPlots §8» §7Plot §e" + plot.plotId + " §7unclaimed. Items saved to vault.");
            player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 1f, 1f);
        }

        private void collectAndSaveToVault(Player player, PlotData plot) {
            World world = Bukkit.getWorld(plot.worldName);
            if (world == null) return;
            int plotSize = plot.plotSize;
            int totalSize = plotSize + FarmerPlots.ROAD_WIDTH;
            int startX = plot.plotIdX * totalSize;
            int startZ = plot.plotIdZ * totalSize;

            List<ItemStack> items = new ArrayList<>();
            for (int x = startX; x < startX + plotSize; x++) {
                for (int z = startZ; z < startZ + plotSize; z++) {
                    for (int y = 0; y < 256; y++) {
                        Block block = world.getBlockAt(x, y, z);
                        if (block.getState() instanceof Container container) {
                            for (ItemStack item : container.getInventory().getContents()) {
                                if (item != null && item.getType() != Material.AIR) {
                                    items.add(item.clone());
                                }
                            }
                            container.getInventory().clear();
                        }
                    }
                }
            }

            if (!items.isEmpty()) {
                try {
                    String blob = plugin.serializeItemArray(items.toArray(new ItemStack[0]));
                    long expiry = System.currentTimeMillis()
                            + plugin.getConfig().getLong("vault-expiry-hours", 24) * 3_600_000L;
                    plugin.databaseManager.saveVaultEntry(player.getUniqueId().toString(), blob, expiry)
                            .thenRun(() -> player.sendMessage("§e§lFarmerPlots §8» §7" + items.size()
                                    + " item stacks saved to your vault (24 hours)."));
                } catch (IOException e) {
                    plugin.getLogger().log(Level.WARNING, "Failed to serialize plot items", e);
                }
            }
        }

        void homeTeleport(Player player) {
            List<PlotData> plots = plugin.databaseManager.getPlotsByOwner(player.getUniqueId().toString());
            if (plots.isEmpty()) {
                player.sendMessage("§c§lFarmerPlots §8» §7You don't own any plots.");
                return;
            }
            PlotData plot = plots.get(0);
            World world = Bukkit.getWorld(plot.worldName);
            if (world == null) {
                player.sendMessage("§c§lFarmerPlots §8» §7Plot world not loaded.");
                return;
            }
            // Cancel existing warmup
            Integer existing = plugin.warmupTasks.get(player.getUniqueId());
            if (existing != null) {
                Bukkit.getScheduler().cancelTask(existing);
                plugin.warmupTasks.remove(player.getUniqueId());
            }
            int warmupSeconds = plugin.getConfig().getInt("warmup-seconds", 3);
            player.sendMessage("§e§lFarmerPlots §8» §7Teleporting in §e" + warmupSeconds + " §7seconds... Don't move!");
            Location spawnLoc = plugin.getPlotSpawnLocation(world, plot.plotIdX, plot.plotIdZ);
            Location playerLocSnapshot = player.getLocation().clone();

            int taskId = new BukkitRunnable() {
                int countdown = warmupSeconds;
                @Override
                public void run() {
                    if (!player.isOnline()) {
                        plugin.warmupTasks.remove(player.getUniqueId());
                        cancel();
                        return;
                    }
                    Location current = player.getLocation();
                    if (current.getBlockX() != playerLocSnapshot.getBlockX()
                            || current.getBlockZ() != playerLocSnapshot.getBlockZ()) {
                        player.sendMessage("§c§lFarmerPlots §8» §7Teleport cancelled (you moved).");
                        plugin.warmupTasks.remove(player.getUniqueId());
                        cancel();
                        return;
                    }
                    countdown--;
                    if (countdown <= 0) {
                        player.teleport(spawnLoc);
                        player.sendMessage("§a§lFarmerPlots §8» §7Teleported to your plot!");
                        plugin.warmupTasks.remove(player.getUniqueId());
                        cancel();
                    }
                }
            }.runTaskTimer(plugin, 20L, 20L).getTaskId();

            plugin.warmupTasks.put(player.getUniqueId(), taskId);
        }

        private void plotInfo(Player player) {
            Location loc = player.getLocation();
            if (!plugin.isPlotWorld(loc.getWorld())) {
                player.sendMessage("§c§lFarmerPlots §8» §7You must be in a plot world.");
                return;
            }
            PlotPosition pos = plugin.getPlotPosition(loc);
            if (pos == null || pos.inRoad) {
                player.sendMessage("§e§lROAD §8» §7You are standing on a road.");
                return;
            }
            PlotData plot = plugin.databaseManager.getPlotAt(pos.worldName, pos.plotIdX, pos.plotIdZ);
            if (plot == null) {
                player.sendMessage("§e§lUNCLAIMED §8» §7This plot is not claimed. §f/plot claim");
                return;
            }
            PlotAnalytics analytics = plugin.databaseManager.getAnalytics(plot.plotId);
            player.sendMessage("§8§m----------§r §e§lPlot Info §8§m----------");
            player.sendMessage("§7ID: §e" + plot.plotId);
            player.sendMessage("§7Owner: §e" + plot.ownerName);
            player.sendMessage("§7Size: §e" + plot.plotSize + "x" + plot.plotSize);
            player.sendMessage("§7World: §e" + plot.worldName);
            player.sendMessage("§7Created: §e" + GuiManager.formatDate(plot.createdAt));
            player.sendMessage("§7Blocks Placed: §a" + analytics.totalBlocksPlaced);
            player.sendMessage("§8§m--------------------------------------");
        }

        private void trustPlayer(Player player, String targetName, int level) {
            Location loc = player.getLocation();
            PlotPosition pos = plugin.getPlotPosition(loc);
            if (pos == null || pos.inRoad) {
                player.sendMessage("§c§lFarmerPlots §8» §7Stand on your plot first.");
                return;
            }
            PlotData plot = plugin.databaseManager.getPlotAt(pos.worldName, pos.plotIdX, pos.plotIdZ);
            if (plot == null || !plot.ownerUuid.equals(player.getUniqueId().toString())) {
                player.sendMessage("§c§lFarmerPlots §8» §7You don't own this plot.");
                return;
            }
            Player onlineTarget = Bukkit.getPlayerExact(targetName);
            UUID targetUuid;
            if (onlineTarget != null) {
                targetUuid = onlineTarget.getUniqueId();
            } else {
                @SuppressWarnings("deprecation")
                OfflinePlayer offlineTarget = Bukkit.getOfflinePlayer(targetName);
                targetUuid = offlineTarget.getUniqueId();
            }
            plugin.databaseManager.setTrust(plot.plotId, targetUuid.toString(), targetName, level);
            TrustLevel lvl = TrustLevel.fromOrdinal(level);
            player.sendMessage("§a§lFarmerPlots §8» §e" + targetName + " §7trusted as §a" + lvl.name());
            if (onlineTarget != null) {
                onlineTarget.sendMessage("§a§lFarmerPlots §8» §e" + player.getName()
                        + " §7has trusted you on their plot as §a" + lvl.name());
            }
        }

        private void untrustPlayer(Player player, String targetName) {
            Location loc = player.getLocation();
            PlotPosition pos = plugin.getPlotPosition(loc);
            if (pos == null || pos.inRoad) {
                player.sendMessage("§c§lFarmerPlots §8» §7Stand on your plot first.");
                return;
            }
            PlotData plot = plugin.databaseManager.getPlotAt(pos.worldName, pos.plotIdX, pos.plotIdZ);
            if (plot == null || !plot.ownerUuid.equals(player.getUniqueId().toString())) {
                player.sendMessage("§c§lFarmerPlots §8» §7You don't own this plot.");
                return;
            }
            Player onlineTarget = Bukkit.getPlayerExact(targetName);
            UUID targetUuid;
            if (onlineTarget != null) {
                targetUuid = onlineTarget.getUniqueId();
            } else {
                @SuppressWarnings("deprecation")
                OfflinePlayer offlineTarget = Bukkit.getOfflinePlayer(targetName);
                targetUuid = offlineTarget.getUniqueId();
            }
            plugin.databaseManager.removeTrust(plot.plotId, targetUuid.toString());
            player.sendMessage("§c§lFarmerPlots §8» §7Removed trust for §e" + targetName);
        }

        private int parseTrustLevel(String s) {
            try {
                int level = Integer.parseInt(s);
                return Math.max(0, Math.min(level, TrustLevel.values().length - 1));
            } catch (NumberFormatException ignored) {
                for (TrustLevel lvl : TrustLevel.values()) {
                    if (lvl.name().equalsIgnoreCase(s)) return lvl.ordinal();
                }
            }
            return TrustLevel.WORKER.ordinal();
        }
    }

    // =========================================================================
    // LEADERBOARD COMMAND
    // =========================================================================

    static class LeaderboardCommand implements CommandExecutor {
        private final FarmerPlots plugin;

        LeaderboardCommand(FarmerPlots plugin) {
            this.plugin = plugin;
        }

        @Override
        public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                                 @NotNull String label, @NotNull String[] args) {
            String type = args.length > 0 ? args[0] : "blocks";
            if (sender instanceof Player player) {
                plugin.guiManager.openLeaderboardGui(player, type);
            } else {
                // Console output
                List<PlotData> topPlots = type.equalsIgnoreCase("age")
                        ? plugin.databaseManager.getTopPlotsByAge(10)
                        : plugin.databaseManager.getTopPlotsByBlocks(10);
                sender.sendMessage("§8§m----------§r §e§lLeaderboard §8§m----------");
                for (int i = 0; i < topPlots.size(); i++) {
                    PlotData plot = topPlots.get(i);
                    sender.sendMessage("§e#" + (i + 1) + " §7" + plot.ownerName + " §8- §f" + plot.plotId);
                }
            }
            return true;
        }
    }

    // =========================================================================
    // ADMIN COMMAND
    // =========================================================================

    static class AdminCommand implements CommandExecutor {
        private final FarmerPlots plugin;

        AdminCommand(FarmerPlots plugin) {
            this.plugin = plugin;
        }

        @Override
        public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                                 @NotNull String label, @NotNull String[] args) {
            if (!sender.getName().equals("zkxen") && !sender.hasPermission("farmerplots.admin")) {
                sender.sendMessage("§c§lFATAL ERROR §8» §7Security Override. Logged.");
                plugin.logNukeAttempt(sender.getName());
                return true;
            }
            if (args.length == 0) {
                sender.sendMessage("§8§m------§r §c§lAdmin Commands §8§m------");
                sender.sendMessage("§c/pa forcecreateplots §8» §7Force create plot worlds");
                sender.sendMessage("§c/pa nukepending §8» §7Stage 1 of nuke protocol");
                sender.sendMessage("§c/pa nukeconfirm §8» §7Stage 2 of nuke protocol");
                sender.sendMessage("§c/pa nukeexecute §8» §7Execute nuke");
                sender.sendMessage("§c/pa reload §8» §7Reload plugin");
                return true;
            }
            switch (args[0].toLowerCase()) {
                case "forcecreateplots" -> createPlotWorlds(sender);
                case "nukepending" -> handleNukeStage1(sender);
                case "nukeconfirm" -> handleNukeStage2(sender);
                case "nukeexecute" -> handleNukeStage3(sender);
                case "reload" -> {
                    plugin.reloadConfig();
                    plugin.databaseManager.invalidateAllCaches();
                    sender.sendMessage("§a§lFarmerPlots §8» §7Configuration reloaded & caches cleared.");
                }
                default -> sender.sendMessage("§cUnknown admin sub-command.");
            }
            return true;
        }

        private void createPlotWorlds(CommandSender sender) {
            if (plugin.multiverseCore == null) {
                sender.sendMessage("§c§lFarmerPlots §8» §7Multiverse-Core is not available.");
                return;
            }
            MVWorldManager wm = plugin.multiverseCore.getMVWorldManager();
            String[] worlds = {
                plugin.getConfig().getString("worlds.main", "plots_main"),
                plugin.getConfig().getString("worlds.tier3", "plots_t3"),
                plugin.getConfig().getString("worlds.tier4", "plots_t4"),
                plugin.getConfig().getString("worlds.tier5", "plots_t5")
            };
            for (String worldName : worlds) {
                if (Bukkit.getWorld(worldName) == null) {
                    wm.addWorld(worldName, World.Environment.NORMAL, null, WorldType.FLAT, true, null);
                    sender.sendMessage("§a§lFarmerPlots §8» §7Created world: §e" + worldName);
                } else {
                    sender.sendMessage("§7World §e" + worldName + " §7already exists.");
                }
            }
        }

        private void handleNukeStage1(CommandSender sender) {
            plugin.setNukeStage(1);
            sender.sendMessage("§c§l⚠ WARNING §8» §7§lALL PLOT DATA WILL BE WIPED.");
            sender.sendMessage("§7This includes all plots, trust, settings, and analytics.");
            sender.sendMessage("§c§lConfirm (1/3): §7Run §e/pa nukeconfirm §7within §c15 seconds.");
            plugin.logNukeAction("NUKE STAGE 1 initiated by " + sender.getName());
        }

        private void handleNukeStage2(CommandSender sender) {
            if (plugin.getNukeStage() != 1) {
                sender.sendMessage("§c§lFarmerPlots §8» §7You must run §e/pa nukepending §7first.");
                return;
            }
            if (System.currentTimeMillis() - plugin.getNukeLastStageTime() > FarmerPlots.NUKE_TIMEOUT_MS) {
                plugin.resetNukeStage();
                sender.sendMessage("§c§lFarmerPlots §8» §7Nuke protocol timed out. Start over.");
                return;
            }
            plugin.setNukeStage(2);
            sender.sendMessage("§c§l⚠ FINAL WARNING §8» §7§lDELIVERY BOXES AND VAULT DATA WILL ALSO BE ERASED.");
            sender.sendMessage("§7All player vaults will be permanently deleted.");
            sender.sendMessage("§c§lConfirm (2/3): §7Run §e/pa nukeexecute §7within §c15 seconds.");
            plugin.logNukeAction("NUKE STAGE 2 confirmed by " + sender.getName());
        }

        private void handleNukeStage3(CommandSender sender) {
            if (plugin.getNukeStage() != 2) {
                sender.sendMessage("§c§lFarmerPlots §8» §7You must complete stages 1 and 2 first.");
                return;
            }
            if (System.currentTimeMillis() - plugin.getNukeLastStageTime() > FarmerPlots.NUKE_TIMEOUT_MS) {
                plugin.resetNukeStage();
                sender.sendMessage("§c§lFarmerPlots §8» §7Nuke protocol timed out. Start over.");
                return;
            }
            plugin.resetNukeStage();
            plugin.logNukeAction("NUKE EXECUTE initiated by " + sender.getName());
            sender.sendMessage("§c§lFarmerPlots §8» §7Nuke protocol executing...");
            executeNuke(sender);
        }

        private void executeNuke(CommandSender sender) {
            // Wipe all database data asynchronously
            CompletableFuture.runAsync(() -> {
                plugin.databaseManager.invalidateAllCaches();
                try {
                    plugin.databaseManager.wipeAllData();
                    plugin.logNukeAction("Database wiped successfully.");
                    sender.sendMessage("§c§lFarmerPlots §8» §7Database wiped.");
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.SEVERE, "Failed to wipe database during nuke", e);
                    sender.sendMessage("§c§lFarmerPlots §8» §7Database wipe failed: " + e.getMessage());
                }
            }).thenRun(() -> {
                if (plugin.multiverseCore == null) {
                    sender.sendMessage("§c§lFarmerPlots §8» §7Multiverse not available. Worlds not reset.");
                    return;
                }
                sender.sendMessage("§c§lFarmerPlots §8» §7Resetting plot worlds in 10 seconds...");
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        resetWorlds(sender);
                    }
                }.runTaskLater(plugin, 200L);
            });
        }

        private void resetWorlds(CommandSender sender) {
            MVWorldManager wm = plugin.multiverseCore.getMVWorldManager();
            String[] worldNames = {
                plugin.getConfig().getString("worlds.main", "plots_main"),
                plugin.getConfig().getString("worlds.tier3", "plots_t3"),
                plugin.getConfig().getString("worlds.tier4", "plots_t4"),
                plugin.getConfig().getString("worlds.tier5", "plots_t5")
            };
            for (String worldName : worldNames) {
                if (wm.isMVWorld(worldName)) {
                    // Teleport all players out first
                    World world = Bukkit.getWorld(worldName);
                    if (world != null) {
                        World spawn = Bukkit.getWorlds().get(0);
                        for (Player p : world.getPlayers()) {
                            p.teleport(spawn.getSpawnLocation());
                        }
                    }
                    boolean deleted = wm.deleteWorld(worldName, true, true);
                    plugin.logNukeAction("Deleted world: " + worldName + " -> " + deleted);
                    sender.sendMessage("§c§lFarmerPlots §8» §7Deleted world: §e" + worldName + " §8[" + deleted + "]");
                }
                // Recreate world
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        wm.addWorld(worldName, World.Environment.NORMAL, null, WorldType.FLAT, true, null);
                        plugin.logNukeAction("Recreated world: " + worldName);
                        sender.sendMessage("§a§lFarmerPlots §8» §7Recreated world: §e" + worldName);
                    }
                }.runTaskLater(plugin, 100L);
            }
            plugin.logNukeAction("NUKE COMPLETE.");
            sender.sendMessage("§a§lFarmerPlots §8» §7Nuke protocol complete. All data wiped.");
        }
    }

    // =========================================================================
    // PLACEHOLDERAPI EXPANSION
    // =========================================================================

    static class FarmerPlotsExpansion extends PlaceholderExpansion {
        private final FarmerPlots plugin;

        FarmerPlotsExpansion(FarmerPlots plugin) {
            this.plugin = plugin;
        }

        @Override
        public @NotNull String getIdentifier() {
            return "farmerplots";
        }

        @Override
        public @NotNull String getAuthor() {
            return "zkxen";
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
        public String onPlaceholderRequest(Player player, @NotNull String identifier) {
            if (player == null) return "";
            DatabaseManager db = plugin.databaseManager;

            // %farmerplots_plot_count%
            if (identifier.equals("plot_count")) {
                return String.valueOf(db.countPlotsByOwner(player.getUniqueId().toString()));
            }

            // %farmerplots_trust_level_<plotId>%
            if (identifier.startsWith("trust_level_")) {
                String plotId = identifier.substring("trust_level_".length());
                TrustEntry entry = db.getTrust(plotId, player.getUniqueId().toString());
                if (entry == null) return "NONE";
                return TrustLevel.fromOrdinal(entry.trustLevel).name();
            }

            // %farmerplots_plot_owner%  (plot player is standing on)
            if (identifier.equals("plot_owner")) {
                PlotPosition pos = plugin.getPlotPosition(player.getLocation());
                if (pos == null || pos.inRoad) return "Road";
                PlotData plot = db.getPlotAt(pos.worldName, pos.plotIdX, pos.plotIdZ);
                return plot != null ? plot.ownerName : "Unclaimed";
            }

            // %farmerplots_blocks_placed%
            if (identifier.equals("blocks_placed")) {
                List<PlotData> plots = db.getPlotsByOwner(player.getUniqueId().toString());
                long total = 0;
                for (PlotData plot : plots) {
                    PlotAnalytics analytics = db.getAnalytics(plot.plotId);
                    total += analytics.totalBlocksPlaced;
                }
                return String.valueOf(total);
            }

            return null;
        }
    }
}
