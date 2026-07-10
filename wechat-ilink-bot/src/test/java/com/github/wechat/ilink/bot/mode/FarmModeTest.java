package com.github.wechat.ilink.bot.mode;

import com.github.wechat.ilink.bot.command.CommandRegistry;
import com.github.wechat.ilink.bot.command.QrCodeProvider;
import com.github.wechat.ilink.bot.engine.CommandParser;
import com.github.wechat.ilink.bot.engine.GameEngine;
import com.github.wechat.ilink.bot.engine.ResponseRenderer;
import com.github.wechat.ilink.bot.farm.FarmGame;
import com.github.wechat.ilink.bot.farm.model.FarmPlot;
import com.github.wechat.ilink.bot.persistence.ActionRankRepository;
import com.github.wechat.ilink.bot.persistence.FarmPlotRepository;
import com.github.wechat.ilink.bot.persistence.DatabaseManager;
import com.github.wechat.ilink.bot.session.PlayerSession;
import com.github.wechat.ilink.bot.session.SessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FarmModeTest {

    @TempDir
    File tempDir;

    private DatabaseManager dbManager;
    private SessionManager sessionManager;
    private GameEngine engine;
    private ModeSender sender;
    private ModeContext ctx;
    private FarmMode farmMode;

    @BeforeEach
    void setUp() {
        String dbPath = new File(tempDir, "farm.db").getAbsolutePath();
        dbManager = new DatabaseManager(dbPath);
        dbManager.initialize();
        sessionManager = new SessionManager(dbManager);
        CommandRegistry registry = new CommandRegistry();
        ActionRankRepository rankRepo = new ActionRankRepository(dbManager);
        new FarmGame(registry, rankRepo, dbManager, new QrCodeProvider() {
            @Override
            public String getQrCodeUrl() { return "qr"; }
        }).registerCommands();
        CommandParser parser = new CommandParser(registry);
        engine = new GameEngine(parser, sessionManager, registry);
        sender = mock(ModeSender.class);
        ctx = ModeContext.builder().sender(sender).engine(engine)
                .renderer(new ResponseRenderer()).sessions(sessionManager).build();
        farmMode = new FarmMode();
    }

    @AfterEach
    void tearDown() {
        dbManager.close();
    }

    @Test
    void type_returnsFarm() {
        assertEquals(BotModeType.FARM, farmMode.type());
    }

    @Test
    void handleText_hashOnly_sendsHint() throws Exception {
        PlayerSession session = new PlayerSession("user1");
        ModeOutcome outcome = farmMode.handleText(ctx, session, "#");
        assertTrue(outcome.isHandled());
        verify(sender).sendText(eq("user1"), contains("帮助"));
    }

    @Test
    void handleText_helpCommand_sendsFarmMenu() throws Exception {
        PlayerSession session = new PlayerSession("user1");

        farmMode.handleText(ctx, session, "#帮助");

        verify(sender).sendText(eq("user1"), contains("帮帮农场"));
    }

    @Test
    void handleText_unknownCommand_sendsError() throws Exception {
        PlayerSession session = new PlayerSession("user1");

        farmMode.handleText(ctx, session, "#不存在的命令xyz");

        verify(sender).sendText(eq("user1"), contains("未知命令"));
    }

    @Test
    void handleText_engineThrows_sendsFallback() throws Exception {
        GameEngine throwingEngine = mock(GameEngine.class);
        when(throwingEngine.dispatch(anyString(), anyString()))
                .thenThrow(new RuntimeException("boom"));
        ModeContext badCtx = ModeContext.builder().sender(sender).engine(throwingEngine)
                .renderer(new ResponseRenderer()).sessions(sessionManager).build();

        PlayerSession session = new PlayerSession("user1");

        farmMode.handleText(badCtx, session, "#帮助");

        verify(sender).sendText(eq("user1"), contains("出了点问题"));
    }

    @Test
    void handleText_shareCommand_sendsImage() throws Exception {
        PlayerSession session = new PlayerSession("user1");

        farmMode.handleText(ctx, session, "#分享");

        verify(sender).sendImage(eq("user1"), any(byte[].class), eq("bot-qrcode.png"), anyString());
    }

    @Test
    void handleText_stealSuccess_pushesVictimNotify() throws Exception {
        // victim 种一块成熟小麦
        FarmPlotRepository plotRepo = new FarmPlotRepository(dbManager);
        FarmPlot plot = new FarmPlot(0);
        plot.plant("wheat");
        plot.setPlantedAt(0L);
        List<FarmPlot> plots = new ArrayList<>();
        plots.add(plot);
        plotRepo.replaceByUserId("victim", plots);

        PlayerSession thief = new PlayerSession("thief");
        farmMode.handleText(ctx, thief, "#偷菜");   // 列候选
        farmMode.handleText(ctx, thief, "#偷菜 1"); // 执行偷取

        verify(sender).sendText(eq("thief"), contains("偷走"));
        verify(sender).sendText(eq("victim"), contains("补偿")); // 辅通道最佳 effort 推送被偷通知
    }

    @Test
    void handleText_victimNotifyFails_swallowsAndStillRepliesToThief() throws Exception {
        FarmPlotRepository plotRepo = new FarmPlotRepository(dbManager);
        FarmPlot plot = new FarmPlot(0);
        plot.plant("wheat");
        plot.setPlantedAt(0L);
        List<FarmPlot> plots = new ArrayList<>();
        plots.add(plot);
        plotRepo.replaceByUserId("victim", plots);
        // 被偷者不活跃：sendText 抛异常（模拟 SDK 无 context token）
        doThrow(new RuntimeException("missing context token")).when(sender).sendText(eq("victim"), anyString());

        PlayerSession thief = new PlayerSession("thief");
        farmMode.handleText(ctx, thief, "#偷菜");
        ModeOutcome outcome = farmMode.handleText(ctx, thief, "#偷菜 1");

        assertTrue(outcome.isHandled()); // 推送失败被吞，不影响偷菜者回执
        verify(sender).sendText(eq("thief"), contains("偷走"));
    }
}
