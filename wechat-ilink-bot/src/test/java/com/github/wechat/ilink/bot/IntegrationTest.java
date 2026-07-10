package com.github.wechat.ilink.bot;

import com.github.wechat.ilink.bot.command.CommandRegistry;
import com.github.wechat.ilink.bot.command.QrCodeProvider;
import com.github.wechat.ilink.bot.engine.CommandParser;
import com.github.wechat.ilink.bot.engine.GameEngine;
import com.github.wechat.ilink.bot.engine.ResponseRenderer;
import com.github.wechat.ilink.bot.farm.FarmGame;
import com.github.wechat.ilink.bot.farm.model.CropStage;
import com.github.wechat.ilink.bot.farm.model.FarmPlot;
import com.github.wechat.ilink.bot.persistence.ActionRankRepository;
import com.github.wechat.ilink.bot.persistence.DatabaseManager;
import com.github.wechat.ilink.bot.session.PlayerSession;
import com.github.wechat.ilink.bot.session.SessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

class IntegrationTest {

    @TempDir
    File tempDir;

    private DatabaseManager dbManager;
    private GameEngine engine;
    private SessionManager sessionManager;
    private ResponseRenderer renderer;

    @BeforeEach
    void setUp() {
        String dbPath = new File(tempDir, "integration.db").getAbsolutePath();
        dbManager = new DatabaseManager(dbPath);
        dbManager.initialize();

        sessionManager = new SessionManager(dbManager);
        CommandRegistry registry = new CommandRegistry();
        renderer = new ResponseRenderer();
        ActionRankRepository rankRepo = new ActionRankRepository(dbManager);

        FarmGame farmGame = new FarmGame(registry, rankRepo, dbManager, new QrCodeProvider() {
            @Override
            public String getQrCodeUrl() { return "test-data"; }
        });
        farmGame.registerCommands();

        CommandParser parser = new CommandParser(registry);
        engine = new GameEngine(parser, sessionManager, registry);
    }

    @AfterEach
    void tearDown() {
        if (dbManager != null) {
            dbManager.close();
        }
    }

    @Test
    void fullFlow_buyPlantHarvestSell() {
        String userId = "farmer1";

        // Step 1: Buy seeds (dispatch receives text AFTER # stripped by GameBot)
        String buyResponse = renderer.render(engine.dispatch(userId, "购买 小麦"));
        assertTrue(buyResponse.contains("购买成功"));

        // Step 2: Plant
        String plantResponse = renderer.render(engine.dispatch(userId, "种植 小麦"));
        assertTrue(plantResponse.contains("种植完成"));

        // Step 3: Verify planted
        PlayerSession afterPlant = sessionManager.getOrCreate(userId);
        int planted = 0;
        for (FarmPlot plot : afterPlant.getActivePlots()) {
            if (plot.getStage() == CropStage.SEED) planted++;
        }
        assertTrue(planted > 0);

        // Step 4: Force mature（成熟由时间驱动，把 plantedAt 拨到 epoch 使 CropGrowth 判成熟）
        for (FarmPlot plot : afterPlant.getActivePlots()) {
            if (plot.getStage() == CropStage.SEED) {
                plot.setPlantedAt(0L);
            }
        }

        // Step 5: Harvest
        String harvestResponse = renderer.render(engine.dispatch(userId, "收获"));
        assertTrue(harvestResponse.contains("收获"));

        // Step 6: Sell
        String sellResponse = renderer.render(engine.dispatch(userId, "卖菜"));
        assertTrue(sellResponse.contains("金币"));

        // Step 7: Verify persistence
        sessionManager.remove(userId);
        PlayerSession reloaded = sessionManager.getOrCreate(userId);
        assertTrue(reloaded.getGold() > 500);
        assertEquals(0, reloaded.getInventory().totalProduceCount());
    }

    @Test
    void fullFlow_checkinAndInfo() {
        String userId = "player1";

        String checkinResponse = renderer.render(engine.dispatch(userId, "签到"));
        assertTrue(checkinResponse.contains("签到成功"));

        String infoResponse = renderer.render(engine.dispatch(userId, "我的信息"));
        assertTrue(infoResponse.contains("农夫")); // 无昵称展示"农夫#尾号"兜底
        assertTrue(infoResponse.contains("560"));
    }

    @Test
    void fullFlow_helpShowsMenu() {
        String response = renderer.render(engine.dispatch("user1", "帮助"));
        assertTrue(response.contains("帮帮农场"));
    }

    @Test
    void fullFlow_viewFarmShowsPlots() {
        String response = renderer.render(engine.dispatch("user1", "农场"));
        assertTrue(response.contains("空地"));
    }

    @Test
    void fullFlow_weatherShowsWeather() {
        String response = renderer.render(engine.dispatch("user1", "天气"));
        assertTrue(response.contains("天气"));
    }

    @Test
    void fullFlow_shopShowsItems() {
        String response = renderer.render(engine.dispatch("user1", "商店"));
        assertTrue(response.contains("小麦"));
    }
}
