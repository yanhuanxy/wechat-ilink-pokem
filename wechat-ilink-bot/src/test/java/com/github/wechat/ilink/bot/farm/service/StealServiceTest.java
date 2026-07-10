package com.github.wechat.ilink.bot.farm.service;

import com.github.wechat.ilink.bot.command.CommandResult;
import com.github.wechat.ilink.bot.farm.model.FarmPlot;
import com.github.wechat.ilink.bot.persistence.ActionRankRepository;
import com.github.wechat.ilink.bot.persistence.DatabaseManager;
import com.github.wechat.ilink.bot.persistence.FarmPlotRepository;
import com.github.wechat.ilink.bot.persistence.PlayerRepository;
import com.github.wechat.ilink.bot.persistence.StealRecordRepository;
import com.github.wechat.ilink.bot.session.PlayerSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StealServiceTest {

    @TempDir
    File tempDir;

    private DatabaseManager dbManager;
    private FarmPlotRepository plotRepo;
    private PlayerRepository playerRepo;
    private StealRecordRepository stealRepo;
    private ActionRankRepository rankRepo;
    private StealService service;

    @BeforeEach
    void setUp() {
        dbManager = new DatabaseManager(new File(tempDir, "test.db").getAbsolutePath());
        dbManager.initialize();
        plotRepo = new FarmPlotRepository(dbManager);
        playerRepo = new PlayerRepository(dbManager);
        stealRepo = new StealRecordRepository(dbManager);
        rankRepo = new ActionRankRepository(dbManager);
        service = new StealService(plotRepo, playerRepo, stealRepo, rankRepo);
    }

    @AfterEach
    void tearDown() {
        dbManager.close();
    }

    /** 给某玩家种一块成熟的小麦（plantedAt=0 → CropGrowth 判成熟）。 */
    private void giveMatureWheat(String userId) {
        List<FarmPlot> plots = new ArrayList<FarmPlot>();
        FarmPlot plot = new FarmPlot(0);
        plot.plant("wheat");
        plot.setPlantedAt(0L);
        plots.add(plot);
        plotRepo.replaceByUserId(userId, plots);
    }

    @Test
    void listCandidates_noActivePlayers_returnsFriendlyMessage() {
        CommandResult result = service.listCandidates("thief");
        assertTrue(result.isSuccess());
        assertTrue(result.getMessage().contains("没有可偷"));
    }

    @Test
    void listCandidates_withMatureVictim_listsVictim() {
        giveMatureWheat("victim");
        CommandResult result = service.listCandidates("thief");
        assertTrue(result.isSuccess());
        assertTrue(result.getMessage().contains("小麦"));
    }

    @Test
    void steal_maturePlot_addsProduceAndRankAndRecord() {
        giveMatureWheat("victim");
        PlayerSession thief = new PlayerSession("thief");
        service.listCandidates("thief");

        CommandResult result = service.stealByIndex(thief, "1");

        assertTrue(result.isSuccess());
        assertTrue(result.getMessage().contains("偷走"));
        assertTrue(thief.getInventory().getProduceCount("wheat") > 0);
        assertTrue(stealRepo.sumStolen("victim", 0, "0") > 0);
        Map<String, Integer> stealRank = rankRepo.getTopScores("STEAL", 10);
        assertTrue(stealRank.containsKey("thief"));
        assertTrue(stealRank.get("thief") > 0);
    }

    @Test
    void steal_withinCooldown_isBlocked() {
        giveMatureWheat("victim");
        PlayerSession thief = new PlayerSession("thief");
        service.listCandidates("thief");
        service.stealByIndex(thief, "1");

        CommandResult second = service.stealByIndex(thief, "1");
        assertFalse(second.isSuccess());
        assertTrue(second.getMessage().contains("手速"));
    }

    @Test
    void stealByIndex_withoutListing_returnsError() {
        PlayerSession thief = new PlayerSession("thief");
        CommandResult result = service.stealByIndex(thief, "1");
        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("先发"));
    }

    @Test
    void stealByIndex_outOfRange_returnsError() {
        giveMatureWheat("victim");
        PlayerSession thief = new PlayerSession("thief");
        service.listCandidates("thief");
        CommandResult result = service.stealByIndex(thief, "9");
        assertFalse(result.isSuccess());
    }

    @Test
    void record_sameThiefSamePlot_cannotStealTwice() {
        // 直接验证 steal_record 的防重复偷（PK 冲突）
        assertTrue(stealRepo.record("victim", 0, "0", "thief", 2));
        assertFalse(stealRepo.record("victim", 0, "0", "thief", 2));
        assertTrue(stealRepo.hasStolen("victim", 0, "0", "thief"));
        assertEquals(2, stealRepo.sumStolen("victim", 0, "0"));
    }
}
