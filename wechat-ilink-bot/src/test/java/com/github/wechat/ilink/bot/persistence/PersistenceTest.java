package com.github.wechat.ilink.bot.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

class PersistenceTest {

    @TempDir
    File tempDir;

    private DatabaseManager dbManager;

    @BeforeEach
    void setUp() {
        String dbPath = new File(tempDir, "test.db").getAbsolutePath();
        dbManager = new DatabaseManager(dbPath);
        dbManager.initialize();
    }

    @AfterEach
    void tearDown() {
        if (dbManager != null) {
            dbManager.close();
        }
    }

    @Test
    void databaseManager_createsTables() {
        assertNotNull(dbManager.getConnection());
    }

    @Test
    void actionRank_incrementAndQuery() {
        ActionRankRepository repo = new ActionRankRepository(dbManager);
        repo.incrementScore("PEST", "user1", 10);
        repo.incrementScore("PEST", "user2", 5);
        repo.incrementScore("PEST", "user1", 3);

        java.util.Map<String, Integer> ranking = repo.getTopScores("PEST", 10);
        assertEquals(2, ranking.size());
    }

    @Test
    void stealRecord_recordsAndAggregatesCompensation() {
        StealRecordRepository repo = new StealRecordRepository(dbManager);
        repo.record("v1", 0, "0", "t1", 3, 9);
        repo.record("v1", 1, "0", "t2", 2, 6);
        assertEquals(9, repo.sumCompensation("v1", 0, "0"));
        assertEquals(6, repo.sumCompensation("v1", 1, "0"));
        assertEquals(15, repo.sumCompensationByVictim("v1"));
        repo.clearPlot("v1", 0);
        assertEquals(0, repo.sumCompensation("v1", 0, "0"));
        assertEquals(6, repo.sumCompensationByVictim("v1")); // plot1 记录仍在
    }

    @Test
    void migration_oldDbWithoutCompensation_addsColumnDefaultZero() throws Exception {
        String oldDbPath = new File(tempDir, "old.db").getAbsolutePath();
        // 原生 JDBC 模拟上线前旧库：steal_record 无 compensation 列，含一条历史记录
        try (java.sql.Connection raw = java.sql.DriverManager.getConnection("jdbc:sqlite:" + oldDbPath);
             java.sql.Statement stmt = raw.createStatement()) {
            stmt.execute("CREATE TABLE steal_record (victim_id TEXT, plot_index INTEGER, planted_at TEXT, "
                    + "thief_id TEXT, amount INTEGER, stolen_at TEXT, "
                    + "PRIMARY KEY (victim_id, plot_index, planted_at, thief_id))");
            stmt.execute("INSERT INTO steal_record VALUES ('v1', 0, '0', 't1', 3, '2024-01-01 00:00:00')");
        }
        // 用 DatabaseManager 打开旧库并初始化，触发幂等迁移加 compensation 列
        DatabaseManager migrated = new DatabaseManager(oldDbPath);
        migrated.initialize();
        StealRecordRepository repo = new StealRecordRepository(migrated);
        assertEquals(0, repo.sumCompensation("v1", 0, "0")); // 历史记录补偿默认 0（不回填）
        repo.record("v1", 1, "0", "t2", 1, 5);
        assertEquals(5, repo.sumCompensationByVictim("v1")); // 新记录正常带补偿
        migrated.close();
    }
}
