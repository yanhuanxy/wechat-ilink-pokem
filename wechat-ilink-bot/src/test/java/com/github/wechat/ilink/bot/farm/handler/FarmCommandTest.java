package com.github.wechat.ilink.bot.farm.handler;

import com.github.wechat.ilink.bot.command.Command;
import com.github.wechat.ilink.bot.command.CommandResult;
import com.github.wechat.ilink.bot.farm.model.CropStage;
import com.github.wechat.ilink.bot.farm.model.FarmPlot;
import com.github.wechat.ilink.bot.persistence.ActionRankRepository;
import com.github.wechat.ilink.bot.persistence.DatabaseManager;
import com.github.wechat.ilink.bot.persistence.StealRecordRepository;
import com.github.wechat.ilink.bot.session.PlayerSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FarmCommandTest {

    @TempDir
    File tempDir;

    private PlayerSession session;
    private DatabaseManager dbManager;
    private ActionRankRepository rankRepo;
    private StealRecordRepository stealRepo;

    @BeforeEach
    void setUp() {
        session = new PlayerSession("testUser");
        dbManager = new DatabaseManager(new File(tempDir, "test.db").getAbsolutePath());
        dbManager.initialize();
        rankRepo = new ActionRankRepository(dbManager);
        stealRepo = new StealRecordRepository(dbManager);
    }

    @AfterEach
    void tearDown() {
        if (dbManager != null) {
            dbManager.close();
        }
    }

    @Test
    void userInfo_displaysPlayerInfo() {
        UserInfoCommand cmd = new UserInfoCommand();
        CommandResult result = cmd.execute(session, new String[0]);
        assertTrue(result.isSuccess());
        // 无昵称时展示"农夫#<wxid尾号>"兜底名，而非裸 userId
        assertTrue(result.getMessage().contains("农夫"));
        assertTrue(result.getMessage().contains("500"));
    }

    @Test
    void viewFarm_displaysEmptyFarm() {
        ViewFarmCommand cmd = new ViewFarmCommand(stealRepo);
        CommandResult result = cmd.execute(session, new String[0]);
        assertTrue(result.isSuccess());
        assertTrue(result.getMessage().contains("空地"));
    }

    @Test
    void plantAll_withSeeds_plantsCrops() {
        session.getInventory().addSeed("wheat", 10);
        PlantAllCommand cmd = new PlantAllCommand();
        CommandResult result = cmd.execute(session, new String[]{"wheat"});
        assertTrue(result.isSuccess());
        assertTrue(result.getMessage().contains("种植完成"));

        List<FarmPlot> activePlots = session.getActivePlots();
        int planted = 0;
        for (FarmPlot plot : activePlots) {
            if (plot.getStage() == CropStage.SEED) planted++;
        }
        assertTrue(planted > 0);
    }

    @Test
    void plantAll_noSeeds_returnsError() {
        PlantAllCommand cmd = new PlantAllCommand();
        CommandResult result = cmd.execute(session, new String[]{"wheat"});
        assertFalse(result.isSuccess());
    }

    @Test
    void plantAll_unknownCrop_returnsError() {
        PlantAllCommand cmd = new PlantAllCommand();
        CommandResult result = cmd.execute(session, new String[]{"不存在"});
        assertFalse(result.isSuccess());
    }

    @Test
    void harvestAll_matureCrops_harvests() {
        // 成熟由时间驱动（CropGrowth 读时计算）：plantedAt 设为很久以前才会判成熟
        plantMature(session.getPlots().get(0), "wheat");
        plantMature(session.getPlots().get(1), "wheat");

        HarvestAllCommand cmd = new HarvestAllCommand(stealRepo);
        CommandResult result = cmd.execute(session, new String[0]);
        assertTrue(result.isSuccess());
        assertTrue(result.getMessage().contains("收获"));
        assertEquals(CropStage.EMPTY, session.getPlots().get(0).getStage());
        assertTrue(session.getInventory().getProduceCount("wheat") > 0);
    }

    @Test
    void harvestAll_noMature_returnsError() {
        HarvestAllCommand cmd = new HarvestAllCommand(stealRepo);
        CommandResult result = cmd.execute(session, new String[0]);
        assertFalse(result.isSuccess());
    }

    @Test
    void waterAll_watersPlantedCrops() {
        session.getPlots().get(0).plant("wheat");
        WaterAllCommand cmd = new WaterAllCommand(rankRepo);
        CommandResult result = cmd.execute(session, new String[0]);
        assertTrue(result.isSuccess());
        assertEquals(1, session.getPlots().get(0).getWaterCount());
    }

    @Test
    void sellAll_withProduce_sellsAll() {
        session.getInventory().addProduce("wheat", 10);
        SellAllCommand cmd = new SellAllCommand();
        CommandResult result = cmd.execute(session, new String[0]);
        assertTrue(result.isSuccess());
        assertTrue(session.getGold() > 500);
        assertEquals(0, session.getInventory().totalProduceCount());
    }

    @Test
    void sellAll_emptyInventory_returnsError() {
        SellAllCommand cmd = new SellAllCommand();
        CommandResult result = cmd.execute(session, new String[0]);
        assertFalse(result.isSuccess());
    }

    @Test
    void clearAll_withWithered_clearsPlots() {
        session.getPlots().get(0).plant("wheat");
        session.getPlots().get(0).setStage(CropStage.WITHERED);
        ClearAllCommand cmd = new ClearAllCommand(rankRepo);
        CommandResult result = cmd.execute(session, new String[0]);
        assertTrue(result.isSuccess());
        assertEquals(CropStage.EMPTY, session.getPlots().get(0).getStage());
    }

    @Test
    void checkin_firstTime_givesReward() {
        CheckinCommand cmd = new CheckinCommand();
        CommandResult result = cmd.execute(session, new String[0]);
        assertTrue(result.isSuccess());
        assertTrue(session.getGold() > 500);
        assertEquals(1, session.getCheckinStreak());
    }

    @Test
    void checkin_duplicate_returnsError() {
        CheckinCommand cmd = new CheckinCommand();
        cmd.execute(session, new String[0]);
        CommandResult result = cmd.execute(session, new String[0]);
        assertFalse(result.isSuccess());
    }

    @Test
    void farmBag_empty_showsEmpty() {
        FarmBagCommand cmd = new FarmBagCommand();
        CommandResult result = cmd.execute(session, new String[0]);
        assertTrue(result.isSuccess());
        assertTrue(result.getMessage().contains("空"));
    }

    @Test
    void farmBag_withItems_showsItems() {
        session.getInventory().addSeed("wheat", 5);
        FarmBagCommand cmd = new FarmBagCommand();
        CommandResult result = cmd.execute(session, new String[0]);
        assertTrue(result.isSuccess());
        assertTrue(result.getMessage().contains("小麦"));
    }

    @Test
    void weather_returnsWeatherInfo() {
        WeatherCommand cmd = new WeatherCommand();
        CommandResult result = cmd.execute(session, new String[0]);
        assertTrue(result.isSuccess());
        assertTrue(result.getMessage().contains("天气"));
    }

    @Test
    void pestAll_noPest_returnsError() {
        PestAllCommand cmd = new PestAllCommand(rankRepo);
        CommandResult result = cmd.execute(session, new String[0]);
        assertFalse(result.isSuccess());
    }

    @Test
    void pestAll_withPest_clearsPest() {
        session.getPlots().get(0).plant("wheat");
        session.getPlots().get(0).setHasPest(true);
        PestAllCommand cmd = new PestAllCommand(rankRepo);
        CommandResult result = cmd.execute(session, new String[0]);
        assertTrue(result.isSuccess());
        assertFalse(session.getPlots().get(0).isHasPest());
    }

    @Test
    void help_displaysMenu() {
        HelpCommand cmd = new HelpCommand();
        CommandResult result = cmd.execute(session, new String[0]);
        assertTrue(result.isSuccess());
        assertTrue(result.getMessage().contains("帮帮农场"));
    }

    @Test
    void fertilize_noFertilizer_returnsError() {
        FertilizeCommand cmd = new FertilizeCommand();
        CommandResult result = cmd.execute(session, new String[]{"wheat"});
        assertFalse(result.isSuccess());
    }

    @Test
    void seedShop_listsAllCrops() {
        SeedShopCommand cmd = new SeedShopCommand();
        CommandResult result = cmd.execute(session, new String[0]);
        assertTrue(result.isSuccess());
        assertTrue(result.getMessage().contains("小麦"));
    }

    @Test
    void couponShop_showsItems() {
        CouponShopCommand cmd = new CouponShopCommand();
        CommandResult result = cmd.execute(session, new String[0]);
        assertTrue(result.isSuccess());
        assertTrue(result.getMessage().contains("点券"));
    }

    @Test
    void toolBag_empty_showsNoTools() {
        ToolBagCommand cmd = new ToolBagCommand();
        CommandResult result = cmd.execute(session, new String[0]);
        assertTrue(result.isSuccess());
        assertTrue(result.getMessage().contains("没有"));
    }

    @Test
    void steal_emptyPool_returnsFriendlyMessage() {
        StealCommand cmd = new StealCommand(new com.github.wechat.ilink.bot.farm.service.StealService(
                new com.github.wechat.ilink.bot.persistence.FarmPlotRepository(dbManager),
                new com.github.wechat.ilink.bot.persistence.PlayerRepository(dbManager),
                stealRepo, rankRepo));
        CommandResult result = cmd.execute(session, new String[0]);
        assertTrue(result.isSuccess());
        assertTrue(result.getMessage().contains("没有可偷"));
    }

    @Test
    void steal_byIndexWithoutListing_returnsError() {
        StealCommand cmd = new StealCommand(new com.github.wechat.ilink.bot.farm.service.StealService(
                new com.github.wechat.ilink.bot.persistence.FarmPlotRepository(dbManager),
                new com.github.wechat.ilink.bot.persistence.PlayerRepository(dbManager),
                stealRepo, rankRepo));
        CommandResult result = cmd.execute(session, new String[]{"1"});
        assertFalse(result.isSuccess());
    }

    @Test
    void buySeed_defaultCount_buysOne() {
        BuySeedCommand cmd = new BuySeedCommand();
        CommandResult result = cmd.execute(session, new String[]{"wheat"});
        assertTrue(result.isSuccess());
        assertTrue(result.getMessage().contains("购买成功"));
        assertEquals(1, session.getInventory().getSeedCount("wheat"));
        assertEquals(490, session.getGold());
    }

    @Test
    void buySeed_xFormat_buysMultiple() {
        BuySeedCommand cmd = new BuySeedCommand();
        CommandResult result = cmd.execute(session, new String[]{"wheat x5"});
        assertTrue(result.isSuccess());
        assertEquals(5, session.getInventory().getSeedCount("wheat"));
        assertEquals(450, session.getGold());
    }

    @Test
    void buySeed_uppercaseX_buysMultiple() {
        BuySeedCommand cmd = new BuySeedCommand();
        CommandResult result = cmd.execute(session, new String[]{"wheat X3"});
        assertTrue(result.isSuccess());
        assertEquals(3, session.getInventory().getSeedCount("wheat"));
        assertEquals(470, session.getGold());
    }

    @Test
    void buySeed_bareNumber_buysMultiple() {
        BuySeedCommand cmd = new BuySeedCommand();
        CommandResult result = cmd.execute(session, new String[]{"wheat 3"});
        assertTrue(result.isSuccess());
        assertEquals(3, session.getInventory().getSeedCount("wheat"));
        assertEquals(470, session.getGold());
    }

    @Test
    void buySeed_unknownCrop_returnsError() {
        BuySeedCommand cmd = new BuySeedCommand();
        CommandResult result = cmd.execute(session, new String[]{"不存在"});
        assertFalse(result.isSuccess());
    }

    @Test
    void buySeed_noArgs_returnsError() {
        BuySeedCommand cmd = new BuySeedCommand();
        CommandResult result = cmd.execute(session, new String[0]);
        assertFalse(result.isSuccess());
    }

    @Test
    void buySeed_cannotAffordAny_returnsError() {
        session.setGold(5);
        BuySeedCommand cmd = new BuySeedCommand();
        CommandResult result = cmd.execute(session, new String[]{"watermelon"});
        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("金币不足"));
        assertEquals(5, session.getGold());
    }

    @Test
    void buySeed_partialPurchase_buysAffordable() {
        session.setGold(25);
        BuySeedCommand cmd = new BuySeedCommand();
        CommandResult result = cmd.execute(session, new String[]{"wheat x3"});
        assertTrue(result.isSuccess());
        assertEquals(2, session.getInventory().getSeedCount("wheat"));
        assertEquals(5, session.getGold());
        assertTrue(result.getMessage().contains("金币不足"));
        assertTrue(result.getMessage().contains("已购买可负担的 2 个"));
    }

    @Test
    void buySeed_invalidXFormat_returnsError() {
        BuySeedCommand cmd = new BuySeedCommand();
        CommandResult result = cmd.execute(session, new String[]{"wheat xabc"});
        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("数量格式错误"));
    }

    @Test
    void plantAll_noArg_usesLastCropAfterBuy() {
        new BuySeedCommand().execute(session, new String[]{"wheat"});
        PlantAllCommand cmd = new PlantAllCommand();
        CommandResult result = cmd.execute(session, new String[0]);
        assertTrue(result.isSuccess());
        assertTrue(result.getMessage().contains("种植完成"));
    }

    @Test
    void plantAll_noArgNoContext_returnsError() {
        PlantAllCommand cmd = new PlantAllCommand();
        CommandResult result = cmd.execute(session, new String[0]);
        assertFalse(result.isSuccess());
    }

    @Test
    void fertilize_noArg_usesLastCropAfterBuy() {
        new BuySeedCommand().execute(session, new String[]{"wheat"});
        session.getInventory().addTool("fertilizer", 1);
        session.getPlots().get(0).plant("wheat");
        FertilizeCommand cmd = new FertilizeCommand();
        CommandResult result = cmd.execute(session, new String[0]);
        assertTrue(result.isSuccess());
    }

    @Test
    void buySeed_bareAlias_buysByAbbreviation() {
        BuySeedCommand cmd = new BuySeedCommand();
        CommandResult result = cmd.execute(session, new String[]{"麦"});
        assertTrue(result.isSuccess());
        assertEquals(1, session.getInventory().getSeedCount("wheat"));
    }

    /** 种下并把 plantedAt 拨到 epoch，使 CropGrowth 读时判定为成熟。 */
    private static void plantMature(FarmPlot plot, String cropKey) {
        plot.plant(cropKey);
        plot.setPlantedAt(0L);
    }
}
