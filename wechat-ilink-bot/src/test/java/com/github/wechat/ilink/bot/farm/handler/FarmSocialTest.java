package com.github.wechat.ilink.bot.farm.handler;

import com.github.wechat.ilink.bot.command.CommandResult;
import com.github.wechat.ilink.bot.farm.model.FarmPlot;
import com.github.wechat.ilink.bot.persistence.ActionRankRepository;
import com.github.wechat.ilink.bot.persistence.DatabaseManager;
import com.github.wechat.ilink.bot.persistence.PlayerRepository;
import com.github.wechat.ilink.bot.persistence.StealRecordRepository;
import com.github.wechat.ilink.bot.session.PlayerSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

class FarmSocialTest {

    @TempDir
    File tempDir;

    private DatabaseManager dbManager;
    private ActionRankRepository rankRepo;
    private PlayerRepository playerRepo;
    private StealRecordRepository stealRepo;
    private PlayerSession session;

    @BeforeEach
    void setUp() {
        dbManager = new DatabaseManager(new File(tempDir, "test.db").getAbsolutePath());
        dbManager.initialize();
        rankRepo = new ActionRankRepository(dbManager);
        playerRepo = new PlayerRepository(dbManager);
        stealRepo = new StealRecordRepository(dbManager);
        session = new PlayerSession("u1");
    }

    @AfterEach
    void tearDown() {
        dbManager.close();
    }

    @Test
    void rename_validName_setsNickname() {
        CommandResult result = new RenameCommand().execute(session, new String[]{"大丰收"});
        assertTrue(result.isSuccess());
        assertEquals("大丰收", session.getNickname());
    }

    @Test
    void rename_empty_returnsError() {
        CommandResult result = new RenameCommand().execute(session, new String[0]);
        assertFalse(result.isSuccess());
    }

    @Test
    void rename_tooLong_returnsError() {
        CommandResult result = new RenameCommand().execute(session, new String[]{"这个昵称实在是太长了超过十二个字符了"});
        assertFalse(result.isSuccess());
    }

    @Test
    void waterAll_writesWaterScore() {
        session.getPlots().get(0).plant("wheat");
        new WaterAllCommand(rankRepo).execute(session, new String[0]);
        assertTrue(rankRepo.getTopScores("WATER", 10).containsKey("u1"));
    }

    @Test
    void pestAll_writesPestScore() {
        session.getPlots().get(0).plant("wheat");
        session.getPlots().get(0).setHasPest(true);
        new PestAllCommand(rankRepo).execute(session, new String[0]);
        assertTrue(rankRepo.getTopScores("PEST", 10).containsKey("u1"));
    }

    @Test
    void clearAll_writesWeedScore() {
        session.getPlots().get(0).plant("wheat");
        session.getPlots().get(0).setStage(com.github.wechat.ilink.bot.farm.model.CropStage.WITHERED);
        new ClearAllCommand(rankRepo).execute(session, new String[0]);
        assertTrue(rankRepo.getTopScores("WEED", 10).containsKey("u1"));
    }

    @Test
    void waterRank_showsNicknameNotWxid() {
        PlayerSession named = new PlayerSession("o9xyzABCD@im.wechat");
        named.setNickname("农夫张三");
        playerRepo.insert(named);
        rankRepo.incrementScore("WATER", "o9xyzABCD@im.wechat", 5);

        CommandResult result = new WaterRankCommand(rankRepo, playerRepo).execute(session, new String[0]);
        assertTrue(result.getMessage().contains("农夫张三"));
        assertFalse(result.getMessage().contains("o9xyzABCD"));
    }

    @Test
    void wealthRank_ordersByGold() {
        PlayerSession rich = new PlayerSession("rich");
        rich.setGold(9999);
        playerRepo.insert(rich);
        CommandResult result = new WealthRankCommand(playerRepo).execute(session, new String[0]);
        assertTrue(result.isSuccess());
        assertTrue(result.getMessage().contains("9999"));
    }

    @Test
    void stealRank_rendersBoard() {
        rankRepo.incrementScore("STEAL", "thief", 120);
        CommandResult result = new StealRankCommand(rankRepo, playerRepo).execute(session, new String[0]);
        assertTrue(result.getMessage().contains("120"));
    }

    @Test
    void rankMenu_listsBoards() {
        CommandResult result = new RankMenuCommand().execute(session, new String[0]);
        assertTrue(result.getMessage().contains("财富榜"));
        assertTrue(result.getMessage().contains("偷菜榜"));
    }

    @Test
    void harvestAll_stolenPlot_yieldsLessAndReports() {
        FarmPlot plot = session.getPlots().get(0);
        plot.plant("wheat");
        plot.setPlantedAt(0L); // 成熟
        stealRepo.record("u1", 0, "0", "thief", 3); // 小麦产量 5，被偷 3

        CommandResult result = new HarvestAllCommand(stealRepo).execute(session, new String[0]);

        assertTrue(result.isSuccess());
        assertEquals(2, session.getInventory().getProduceCount("wheat"));
        assertTrue(result.getMessage().contains("被偷"));
        assertEquals(0, stealRepo.sumStolen("u1", 0, "0")); // 收获后清理
    }
}
