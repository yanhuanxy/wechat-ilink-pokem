package com.github.wechat.ilink.bot.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public class DatabaseManager {

    private static final Logger log = LoggerFactory.getLogger(DatabaseManager.class);

    private final String dbPath;
    private Connection connection;

    public DatabaseManager(String dbPath) {
        this.dbPath = dbPath;
    }

    public void initialize() {
        try {
            java.nio.file.Path parent = Paths.get(dbPath).getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            connection.setAutoCommit(true);

            try (Statement stmt = connection.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL");
                stmt.execute("PRAGMA synchronous=NORMAL");
                stmt.execute("PRAGMA foreign_keys=ON");
            }

            executeSchema();
            log.info("SQLite 数据库初始化完成: {}", dbPath);
        } catch (Exception e) {
            throw new RuntimeException("数据库初始化失败", e);
        }
    }

    private void executeSchema() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS player (" +
                    "user_id TEXT PRIMARY KEY," +
                    "gold INTEGER NOT NULL DEFAULT 500," +
                    "exp INTEGER NOT NULL DEFAULT 0," +
                    "level INTEGER NOT NULL DEFAULT 1," +
                    "max_plots INTEGER NOT NULL DEFAULT 4," +
                    "coupon INTEGER NOT NULL DEFAULT 0," +
                    "last_checkin TEXT," +
                    "checkin_streak INTEGER NOT NULL DEFAULT 0," +
                    "created_at TEXT NOT NULL," +
                    "updated_at TEXT NOT NULL)");

            stmt.execute("CREATE TABLE IF NOT EXISTS farm_plot (" +
                    "user_id TEXT NOT NULL," +
                    "plot_index INTEGER NOT NULL," +
                    "crop_type TEXT," +
                    "stage TEXT NOT NULL DEFAULT 'EMPTY'," +
                    "water_count INTEGER NOT NULL DEFAULT 0," +
                    "planted_at TEXT," +
                    "has_pest INTEGER NOT NULL DEFAULT 0," +
                    "has_weed INTEGER NOT NULL DEFAULT 0," +
                    "PRIMARY KEY (user_id, plot_index))");

            stmt.execute("CREATE TABLE IF NOT EXISTS inventory (" +
                    "user_id TEXT NOT NULL," +
                    "item_type TEXT NOT NULL," +
                    "item_key TEXT NOT NULL," +
                    "quantity INTEGER NOT NULL DEFAULT 0," +
                    "PRIMARY KEY (user_id, item_type, item_key))");

            stmt.execute("CREATE TABLE IF NOT EXISTS action_rank (" +
                    "action_type TEXT NOT NULL," +
                    "user_id TEXT NOT NULL," +
                    "score INTEGER NOT NULL DEFAULT 0," +
                    "updated_at TEXT NOT NULL," +
                    "PRIMARY KEY (action_type, user_id))");

            // 偷菜记录：记"某玩家某地某成熟周期被某贼偷走多少"。planted_at 区分作物轮次；
            // 收获时按 (victim_id, plot_index) 清理。全字段 PK 保证同一贼对同一地同一轮只能偷一次。
            stmt.execute("CREATE TABLE IF NOT EXISTS steal_record (" +
                    "victim_id TEXT NOT NULL," +
                    "plot_index INTEGER NOT NULL," +
                    "planted_at TEXT NOT NULL," +
                    "thief_id TEXT NOT NULL," +
                    "amount INTEGER NOT NULL DEFAULT 0," +
                    "compensation INTEGER NOT NULL DEFAULT 0," +
                    "stolen_at TEXT NOT NULL," +
                    "PRIMARY KEY (victim_id, plot_index, planted_at, thief_id))");

            stmt.execute("CREATE TABLE IF NOT EXISTS claude_sessions (" +
                    "session_id TEXT PRIMARY KEY," +
                    "user_id TEXT NOT NULL," +
                    "cwd TEXT," +
                    "model TEXT," +
                    "title TEXT," +
                    "created_at INTEGER NOT NULL," +
                    "updated_at INTEGER NOT NULL)");

            stmt.execute("CREATE TABLE IF NOT EXISTS bot_session (" +
                    "name TEXT PRIMARY KEY," +
                    "bot_token TEXT," +
                    "user_id TEXT," +
                    "bot_id TEXT," +
                    "base_url TEXT," +
                    "updates_cursor TEXT," +
                    "updated_at INTEGER NOT NULL)");

            stmt.execute("CREATE TABLE IF NOT EXISTS processed_message (" +
                    "user_id TEXT PRIMARY KEY," +
                    "last_message_id INTEGER NOT NULL," +
                    "updated_at INTEGER NOT NULL)");

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_farm_plot_user ON farm_plot(user_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_inventory_user ON inventory(user_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_action_rank_type ON action_rank(action_type, score DESC)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_claude_sessions_user ON claude_sessions(user_id, updated_at DESC)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_steal_victim ON steal_record(victim_id)");

            migratePlayerBotMode(stmt);
            migratePlayerNickname(stmt);
            migrateStealRecordCompensation(stmt);
        }
    }

    public Connection getConnection() {
        return connection;
    }

    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            log.error("关闭数据库连接失败", e);
        }
    }

    private void migratePlayerBotMode(Statement stmt) throws SQLException {
        if (hasPlayerBotModeColumn(stmt)) {
            return;
        }
        stmt.execute("ALTER TABLE player ADD COLUMN bot_mode TEXT");
        log.info("已迁移 player 表：新增 bot_mode 列");
    }

    private boolean hasPlayerBotModeColumn(Statement stmt) throws SQLException {
        return hasPlayerColumn(stmt, "bot_mode");
    }

    private void migratePlayerNickname(Statement stmt) throws SQLException {
        if (hasPlayerColumn(stmt, "nickname")) {
            return;
        }
        stmt.execute("ALTER TABLE player ADD COLUMN nickname TEXT");
        log.info("已迁移 player 表：新增 nickname 列");
    }

    private boolean hasPlayerColumn(Statement stmt, String column) throws SQLException {
        try (java.sql.ResultSet rs = stmt.executeQuery("PRAGMA table_info(player)")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    return true;
                }
            }
        }
        return false;
    }

    // 被偷补偿列：已上线库历史无此列时补加。纯加法、默认 0、幂等——历史记录补偿 0（收获时补偿 0，行为同迁移前）。
    private void migrateStealRecordCompensation(Statement stmt) throws SQLException {
        if (hasStealRecordColumn(stmt, "compensation")) {
            return;
        }
        stmt.execute("ALTER TABLE steal_record ADD COLUMN compensation INTEGER NOT NULL DEFAULT 0");
        log.info("已迁移 steal_record 表：新增 compensation 列");
    }

    private boolean hasStealRecordColumn(Statement stmt, String column) throws SQLException {
        try (java.sql.ResultSet rs = stmt.executeQuery("PRAGMA table_info(steal_record)")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    return true;
                }
            }
        }
        return false;
    }
}
