package com.github.wechat.ilink.bot.mode;

import com.github.wechat.ilink.bot.mode.claude.ClaudeSession;
import com.github.wechat.ilink.bot.persistence.ClaudeSessionRepository;
import com.github.wechat.ilink.bot.persistence.DatabaseManager;
import com.github.wechat.ilink.bot.session.PlayerSession;
import com.github.wechat.ilink.bot.session.SessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SystemCommandModeTest {

    @TempDir
    File tempDir;

    private DatabaseManager dbManager;
    private SessionManager sessionManager;
    private ClaudeSessionRepository repo;
    private ModeSender sender;
    private ModeContext ctx;
    private SystemCommandMode mode;

    @BeforeEach
    void setUp() {
        String dbPath = new File(tempDir, "sys.db").getAbsolutePath();
        dbManager = new DatabaseManager(dbPath);
        dbManager.initialize();
        sessionManager = new SessionManager(dbManager);
        repo = new ClaudeSessionRepository(dbManager);
        sender = mock(ModeSender.class);
        ctx = ModeContext.builder().sender(sender).sessions(sessionManager).claudeSessionRepo(repo).build();
        mode = new SystemCommandMode();
    }

    @AfterEach
    void tearDown() {
        dbManager.close();
    }

    @Test
    void handleText_modeClaude_switchesAndPersists() throws Exception {
        PlayerSession session = sessionManager.getOrCreate("user1");

        mode.handleText(ctx, session, "/mode claude");

        assertEquals(BotModeType.CLAUDE, session.getCurrentMode());
        verify(sender).sendText(eq("user1"), contains("Claude Bridge"));
    }

    @Test
    void handleText_modeFarm_rejectsWithoutSwitch() throws Exception {
        PlayerSession session = sessionManager.getOrCreate("user1");

        mode.handleText(ctx, session, "/mode farm");

        assertNotEquals(BotModeType.FARM, session.getCurrentMode());
        verify(sender).sendText(eq("user1"), contains("无需切换"));
    }

    @Test
    void handleText_new_clearsActiveClaudeSession() throws Exception {
        PlayerSession session = sessionManager.getOrCreate("user1");
        session.setActiveClaudeSessionId("old-sid");

        mode.handleText(ctx, session, "/new");

        assertNull(session.getActiveClaudeSessionId());
        verify(sender).sendText(eq("user1"), contains("新的 Claude 会话"));
    }

    @Test
    void handleText_sessions_empty_promptsNoHistory() throws Exception {
        PlayerSession session = sessionManager.getOrCreate("user1");

        mode.handleText(ctx, session, "/sessions");

        verify(sender).sendText(eq("user1"), contains("暂无历史"));
    }

    @Test
    void handleText_sessions_listsByIndex() throws Exception {
        repo.insert(new ClaudeSession("sid-a", "user1", null, null, "会话A", 1L, 1000L));
        repo.insert(new ClaudeSession("sid-b", "user1", null, null, "会话B", 1L, 2000L));
        PlayerSession session = sessionManager.getOrCreate("user1");

        mode.handleText(ctx, session, "/sessions");

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(sender).sendText(eq("user1"), captor.capture());
        String text = captor.getValue();
        assertTrue(text.contains("会话B"));
        assertTrue(text.contains("会话A"));
        assertTrue(text.contains("1."));
    }

    @Test
    void handleText_resume_byIndex_setsActiveAndSwitchesMode() throws Exception {
        repo.insert(new ClaudeSession("sid-a", "user1", null, null, "会话A", 1L, 1000L));
        PlayerSession session = sessionManager.getOrCreate("user1");

        mode.handleText(ctx, session, "/resume 1");

        assertEquals("sid-a", session.getActiveClaudeSessionId());
        assertEquals(BotModeType.CLAUDE, session.getCurrentMode());
        verify(sender).sendText(eq("user1"), contains("已恢复"));
    }

    @Test
    void handleText_resume_byId_setsActive() throws Exception {
        repo.insert(new ClaudeSession("sid-xyz", "user1", null, null, "会话X", 1L, 1000L));
        PlayerSession session = sessionManager.getOrCreate("user1");

        mode.handleText(ctx, session, "/resume sid-xyz");

        assertEquals("sid-xyz", session.getActiveClaudeSessionId());
    }

    @Test
    void handleText_resume_unknownIndex_sendsNotFound() throws Exception {
        PlayerSession session = sessionManager.getOrCreate("user1");

        mode.handleText(ctx, session, "/resume 99");

        verify(sender).sendText(eq("user1"), contains("未找到"));
        assertNull(session.getActiveClaudeSessionId());
    }

    @Test
    void handleText_resume_otherUsersSession_sendsNotFound() throws Exception {
        repo.insert(new ClaudeSession("sid-other", "user2", null, null, "他人", 1L, 1000L));
        PlayerSession session = sessionManager.getOrCreate("user1");

        mode.handleText(ctx, session, "/resume sid-other");

        verify(sender).sendText(eq("user1"), contains("未找到"));
        assertNull(session.getActiveClaudeSessionId());
    }

    @Test
    void handleText_help_listsClaudeCommands() throws Exception {
        PlayerSession session = sessionManager.getOrCreate("user1");

        mode.handleText(ctx, session, "/help");

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(sender).sendText(eq("user1"), captor.capture());
        String text = captor.getValue();
        assertTrue(text.contains("/new"));
        assertTrue(text.contains("/sessions"));
        assertTrue(text.contains("/resume"));
    }

    @Test
    void sudo_on_adminUser_enablesPrivileged() throws Exception {
        SystemCommandMode adminMode = new SystemCommandMode(new HashSet<String>(Arrays.asList("admin1")));
        PlayerSession session = sessionManager.getOrCreate("admin1");

        adminMode.handleText(ctx, session, "/sudo on");

        assertTrue(session.isClaudePrivileged());
        verify(sender).sendText(eq("admin1"), contains("已开启"));
    }

    @Test
    void sudo_on_nonAdmin_deniedAsUnknown() throws Exception {
        SystemCommandMode adminMode = new SystemCommandMode(new HashSet<String>(Arrays.asList("admin1")));
        PlayerSession session = sessionManager.getOrCreate("guest");

        adminMode.handleText(ctx, session, "/sudo on");

        assertFalse(session.isClaudePrivileged(), "非管理员不得提权");
        verify(sender).sendText(eq("guest"), contains("未知命令"));
    }

    @Test
    void sudo_off_clearsPrivileged() throws Exception {
        SystemCommandMode adminMode = new SystemCommandMode(new HashSet<String>(Arrays.asList("admin1")));
        PlayerSession session = sessionManager.getOrCreate("admin1");
        session.setClaudePrivileged(true);

        adminMode.handleText(ctx, session, "/sudo off");

        assertFalse(session.isClaudePrivileged());
        verify(sender).sendText(eq("admin1"), contains("已关闭"));
    }

    @Test
    void sudo_status_reportsState() throws Exception {
        SystemCommandMode adminMode = new SystemCommandMode(new HashSet<String>(Arrays.asList("admin1")));
        PlayerSession session = sessionManager.getOrCreate("admin1");

        adminMode.handleText(ctx, session, "/sudo status");

        assertFalse(session.isClaudePrivileged());
        verify(sender).sendText(eq("admin1"), contains("已关闭"));
    }

    @Test
    void handleMode_adminWithDefaultPrivileged_setsPrivileged() throws Exception {
        SystemCommandMode adminMode = new SystemCommandMode(new HashSet<String>(Arrays.asList("admin1")), true);
        PlayerSession session = sessionManager.getOrCreate("admin1");

        adminMode.handleText(ctx, session, "/mode claude");

        assertEquals(BotModeType.CLAUDE, session.getCurrentMode());
        assertTrue(session.isClaudePrivileged(), "admin + 默认提权开关应进入模式即提权");
        verify(sender).sendText(eq("admin1"), contains("无限制模式"));
    }

    @Test
    void handleMode_nonAdminWithDefaultPrivileged_staysRestricted() throws Exception {
        SystemCommandMode adminMode = new SystemCommandMode(new HashSet<String>(Arrays.asList("admin1")), true);
        PlayerSession session = sessionManager.getOrCreate("guest");

        adminMode.handleText(ctx, session, "/mode claude");

        assertEquals(BotModeType.CLAUDE, session.getCurrentMode());
        assertFalse(session.isClaudePrivileged(), "非管理员即便开关打开也不得默认提权");
    }

    @Test
    void handleMode_flagOff_staysRestricted() throws Exception {
        SystemCommandMode adminMode = new SystemCommandMode(new HashSet<String>(Arrays.asList("admin1")), false);
        PlayerSession session = sessionManager.getOrCreate("admin1");

        adminMode.handleText(ctx, session, "/mode claude");

        assertEquals(BotModeType.CLAUDE, session.getCurrentMode());
        assertFalse(session.isClaudePrivileged(), "开关关闭时 admin 仍为受限档（回归护栏）");
    }

    @Test
    void sudo_emptyAdminSet_neverEscalates() throws Exception {
        PlayerSession session = sessionManager.getOrCreate("anyone");

        mode.handleText(ctx, session, "/sudo on");

        assertFalse(session.isClaudePrivileged(), "空白名单时任何人都不能提权");
        verify(sender).sendText(eq("anyone"), contains("未知命令"));
    }

    @Test
    void help_nonAdmin_omitsSudo() throws Exception {
        PlayerSession session = sessionManager.getOrCreate("user1");

        mode.handleText(ctx, session, "/help");

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(sender).sendText(eq("user1"), captor.capture());
        assertFalse(captor.getValue().contains("/sudo"), "非管理员帮助里不应出现 /sudo");
    }

    @Test
    void help_admin_includesSudo() throws Exception {
        SystemCommandMode adminMode = new SystemCommandMode(new HashSet<String>(Arrays.asList("admin1")));
        PlayerSession session = sessionManager.getOrCreate("admin1");

        adminMode.handleText(ctx, session, "/help");

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(sender).sendText(eq("admin1"), captor.capture());
        assertTrue(captor.getValue().contains("/sudo"), "管理员帮助里应出现 /sudo");
    }

    @Test
    void plan_on_enablesPlanAndClearsPrivileged() throws Exception {
        PlayerSession session = sessionManager.getOrCreate("user1");
        session.setClaudePrivileged(true);

        mode.handleText(ctx, session, "/plan on");

        assertTrue(session.isClaudePlanMode());
        assertFalse(session.isClaudePrivileged(), "开 plan 应互斥清掉 privileged");
        verify(sender).sendText(eq("user1"), contains("计划模式"));
    }

    @Test
    void plan_off_clearsPlan() throws Exception {
        PlayerSession session = sessionManager.getOrCreate("user1");
        session.setClaudePlanMode(true);

        mode.handleText(ctx, session, "/plan off");

        assertFalse(session.isClaudePlanMode());
        verify(sender).sendText(eq("user1"), contains("已关闭"));
    }

    @Test
    void plan_status_reportsState() throws Exception {
        PlayerSession session = sessionManager.getOrCreate("user1");

        mode.handleText(ctx, session, "/plan");

        verify(sender).sendText(eq("user1"), contains("已关闭"));
    }

    @Test
    void plan_availableToNonAdmin() throws Exception {
        PlayerSession session = sessionManager.getOrCreate("guest");

        mode.handleText(ctx, session, "/plan on");

        assertTrue(session.isClaudePlanMode(), "非管理员也能用 /plan（只读，不限白名单）");
        verify(sender).sendText(eq("guest"), contains("计划模式"));
    }

    @Test
    void approve_noActiveSession_rejects() throws Exception {
        PlayerSession session = sessionManager.getOrCreate("user1");

        mode.handleText(ctx, session, "/approve");

        assertFalse(session.isClaudeApprovedExec());
        verify(sender).sendText(eq("user1"), contains("没有可执行"));
    }

    @Test
    void approve_admin_setsApprovedAndPrivileged() throws Exception {
        SystemCommandMode adminMode = new SystemCommandMode(new HashSet<String>(Arrays.asList("admin1")));
        PlayerSession session = sessionManager.getOrCreate("admin1");
        session.setActiveClaudeSessionId("sid-1");

        adminMode.handleText(ctx, session, "/approve");

        assertTrue(session.isClaudeApprovedExec());
        assertTrue(session.isClaudePrivileged(), "admin /approve 应自动切 bypass");
        verify(sender).sendText(eq("admin1"), contains("已提权"));
    }

    @Test
    void approve_nonAdmin_setsApprovedKeepsReadOnly() throws Exception {
        PlayerSession session = sessionManager.getOrCreate("guest");
        session.setActiveClaudeSessionId("sid-1");

        mode.handleText(ctx, session, "/approve");

        assertTrue(session.isClaudeApprovedExec(), "非 admin 也置 approved 标志");
        assertFalse(session.isClaudePrivileged(), "非 admin 不提权");
        verify(sender).sendText(eq("guest"), contains("非管理员"));
    }

    @Test
    void sudo_on_clearsPlan() throws Exception {
        SystemCommandMode adminMode = new SystemCommandMode(new HashSet<String>(Arrays.asList("admin1")));
        PlayerSession session = sessionManager.getOrCreate("admin1");
        session.setClaudePlanMode(true);

        adminMode.handleText(ctx, session, "/sudo on");

        assertTrue(session.isClaudePrivileged());
        assertFalse(session.isClaudePlanMode(), "/sudo on 应互斥清掉 plan");
    }
}
