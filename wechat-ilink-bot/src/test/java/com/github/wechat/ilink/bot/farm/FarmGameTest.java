package com.github.wechat.ilink.bot.farm;

import com.github.wechat.ilink.bot.command.CommandRegistry;
import com.github.wechat.ilink.bot.command.QrCodeProvider;
import com.github.wechat.ilink.bot.persistence.ActionRankRepository;
import com.github.wechat.ilink.bot.persistence.DatabaseManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

class FarmGameTest {

    @TempDir
    File tempDir;

    private DatabaseManager dbManager;
    private CommandRegistry registry;
    private QrCodeProvider qrCodeProvider;

    @BeforeEach
    void setUp() {
        String dbPath = new File(tempDir, "test.db").getAbsolutePath();
        dbManager = new DatabaseManager(dbPath);
        dbManager.initialize();
        registry = new CommandRegistry();
        qrCodeProvider = new QrCodeProvider() {
            @Override
            public String getQrCodeUrl() { return "test-data"; }
        };
    }

    @AfterEach
    void tearDown() {
        if (dbManager != null) {
            dbManager.close();
        }
    }

    @Test
    void registerCommands_registersAllCommands() {
        ActionRankRepository rankRepo = new ActionRankRepository(dbManager);
        FarmGame farmGame = new FarmGame(registry, rankRepo, dbManager, qrCodeProvider);
        farmGame.registerCommands();

        assertNotNull(registry.find("USER_INFO"));
        assertNotNull(registry.find("VIEW_FARM"));
        assertNotNull(registry.find("SEED_SHOP"));
        assertNotNull(registry.find("COUPON_SHOP"));
        assertNotNull(registry.find("FARM_BAG"));
        assertNotNull(registry.find("TOOL_BAG"));
        assertNotNull(registry.find("CHECKIN"));
        assertNotNull(registry.find("WEATHER"));
        assertNotNull(registry.find("PLANT_ALL"));
        assertNotNull(registry.find("HARVEST_ALL"));
        assertNotNull(registry.find("SELL_ALL"));
        assertNotNull(registry.find("CLEAR_ALL"));
        assertNotNull(registry.find("WATER_ALL"));
        assertNotNull(registry.find("PEST_ALL"));
        assertNotNull(registry.find("FERTILIZE"));
        assertNotNull(registry.find("RENAME"));
        assertNotNull(registry.find("STEAL"));
        assertNotNull(registry.find("PEST_RANK"));
        assertNotNull(registry.find("WEED_RANK"));
        assertNotNull(registry.find("WATER_RANK"));
        assertNotNull(registry.find("WEALTH_RANK"));
        assertNotNull(registry.find("LEVEL_RANK"));
        assertNotNull(registry.find("STEAL_RANK"));
        assertNotNull(registry.find("RANK_MENU"));
        assertNotNull(registry.find("HELP"));
        assertNotNull(registry.find("BUY_SEED"));
        assertNotNull(registry.find("SHARE"));

        assertEquals(27, registry.allCommands().size());
    }

    @Test
    void registerCommands_registersChineseAliases() {
        ActionRankRepository rankRepo = new ActionRankRepository(dbManager);
        FarmGame farmGame = new FarmGame(registry, rankRepo, dbManager, qrCodeProvider);
        farmGame.registerCommands();

        assertEquals("USER_INFO", registry.resolveAlias("我的信息"));
        assertEquals("VIEW_FARM", registry.resolveAlias("农场"));
        assertEquals("CHECKIN", registry.resolveAlias("签到"));
        assertEquals("HELP", registry.resolveAlias("帮助"));
        assertEquals("BUY_SEED", registry.resolveAlias("购买"));
        assertEquals("BUY_SEED", registry.resolveAlias("买种子"));
    }
}
