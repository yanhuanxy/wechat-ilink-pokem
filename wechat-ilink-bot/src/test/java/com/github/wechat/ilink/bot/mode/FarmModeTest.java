package com.github.wechat.ilink.bot.mode;

import com.github.wechat.ilink.bot.command.CommandRegistry;
import com.github.wechat.ilink.bot.command.QrCodeProvider;
import com.github.wechat.ilink.bot.engine.CommandParser;
import com.github.wechat.ilink.bot.engine.GameEngine;
import com.github.wechat.ilink.bot.engine.ResponseRenderer;
import com.github.wechat.ilink.bot.farm.FarmGame;
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
}
