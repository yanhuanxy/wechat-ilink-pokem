package com.github.wechat.ilink.bot.mode;

import com.github.wechat.ilink.bot.mode.claude.ClaudeSession;
import com.github.wechat.ilink.bot.mode.hook.HookContext;
import com.github.wechat.ilink.bot.mode.hook.HookEvent;
import com.github.wechat.ilink.bot.mode.hook.HookVerdict;
import com.github.wechat.ilink.bot.persistence.ClaudeSessionRepository;
import com.github.wechat.ilink.bot.session.PlayerSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Set;

public class SystemCommandMode implements BotMode {

    private static final Logger log = LoggerFactory.getLogger(SystemCommandMode.class);

    private static final int SESSIONS_LIST_LIMIT = 10;

    private final Set<String> claudeAdminUsers;
    // true 时白名单内 admin 进入 CLAUDE 模式即默认切提权档，省去开场 /sudo on（remote 效率）。默认 false。
    private final boolean adminDefaultPrivileged;

    public SystemCommandMode() {
        this(Collections.<String>emptySet(), false);
    }

    public SystemCommandMode(Set<String> claudeAdminUsers) {
        this(claudeAdminUsers, false);
    }

    public SystemCommandMode(Set<String> claudeAdminUsers, boolean adminDefaultPrivileged) {
        this.claudeAdminUsers = claudeAdminUsers;
        this.adminDefaultPrivileged = adminDefaultPrivileged;
    }

    @Override
    public BotModeType type() {
        return BotModeType.CHAT;
    }

    @Override
    public ModeOutcome handleText(ModeContext ctx, PlayerSession session, String text) {
        String userId = session.getUserId();
        String body = text.substring(1).trim();
        if (body.isEmpty()) {
            sendHelp(ctx, userId);
            return ModeOutcome.handled();
        }

        String[] parts = body.split("\\s+", 2);
        String cmd = parts[0].toLowerCase();
        String arg = parts.length > 1 ? parts[1].trim() : "";

        if ("mode".equals(cmd)) {
            return handleMode(ctx, session, arg);
        }
        if ("help".equals(cmd)) {
            sendHelp(ctx, userId);
            return ModeOutcome.handled();
        }
        if ("status".equals(cmd)) {
            sendStatus(ctx, session);
            return ModeOutcome.handled();
        }
        if ("new".equals(cmd)) {
            return handleNew(ctx, session);
        }
        if ("sessions".equals(cmd)) {
            return handleSessions(ctx, session);
        }
        if ("resume".equals(cmd)) {
            return handleResume(ctx, session, arg);
        }
        if ("plan".equals(cmd)) {
            return handlePlan(ctx, session, arg);
        }
        if ("approve".equals(cmd)) {
            return handleApprove(ctx, session);
        }
        if ("sudo".equals(cmd)) {
            return handleSudo(ctx, session, arg);
        }
        sendUnknown(ctx, userId, cmd);
        return ModeOutcome.handled();
    }

    /**
     * /sudo on|off|status —— 管理员经白名单提权，使本会话 Claude 子进程以无限制模式运行（管控宿主机）。
     * 非管理员命中即当作未知命令，不暴露该指令存在。
     */
    private ModeOutcome handleSudo(ModeContext ctx, PlayerSession session, String arg) {
        String userId = session.getUserId();
        if (!isAdmin(userId)) {
            sendUnknown(ctx, userId, "sudo");
            return ModeOutcome.handled();
        }
        String action = arg.isEmpty() ? "status" : arg.toLowerCase();
        if ("on".equals(action)) {
            session.setClaudePrivileged(true);
            session.setClaudePlanMode(false);   // 互斥：bypass 与 plan 不可并存
            send(ctx, userId, "🔓 已开启 Claude 无限制模式（本会话生效，重启或 /sudo off 后回收）。\n"
                    + "现在可让 Claude 读写/执行本机任意路径；用完请及时 /sudo off。");
        } else if ("off".equals(action)) {
            session.setClaudePrivileged(false);
            send(ctx, userId, "🔒 已关闭 Claude 无限制模式，回到只读工作目录。");
        } else {
            send(ctx, userId, "Claude 无限制模式：" + (session.isClaudePrivileged() ? "已开启 🔓" : "已关闭 🔒")
                    + "\n用法：/sudo on | off");
        }
        return ModeOutcome.handled();
    }

    /**
     * /plan on|off|status —— 切换 Claude 计划模式（--permission-mode plan，只读探索产出方案文本）。
     * 对所有用户开放（plan 模式只读，与 default 同安全级）；与 /sudo 互斥。
     */
    private ModeOutcome handlePlan(ModeContext ctx, PlayerSession session, String arg) {
        String userId = session.getUserId();
        String action = arg.isEmpty() ? "status" : arg.toLowerCase();
        if ("on".equals(action)) {
            session.setClaudePlanMode(true);
            session.setClaudePrivileged(false);   // 互斥：bypass 与 plan 不可并存
            send(ctx, userId, "📋 已开启 Claude 计划模式（只读分析，产出方案文本）。\n"
                    + "用 /approve 批准后发送消息即可执行。");
        } else if ("off".equals(action)) {
            session.setClaudePlanMode(false);
            send(ctx, userId, "已关闭计划模式，回到默认只读。");
        } else {
            send(ctx, userId, "Claude 计划模式：" + (session.isClaudePlanMode() ? "已开启 📋" : "已关闭")
                    + "\n用法：/plan on | off");
        }
        return ModeOutcome.handled();
    }

    /**
     * /approve —— 批准执行上一轮计划。置位一次性 approved 标志（下一条消息由 ClaudeBridgeMode 消费）；
     * 管理员自动切 bypass 档以获得完整写/执行能力，非管理员保持当前只读档（写操作将被拒）。
     * 对所有用户开放；完整执行能力仅管理员。
     */
    private ModeOutcome handleApprove(ModeContext ctx, PlayerSession session) {
        String userId = session.getUserId();
        String sid = session.getActiveClaudeSessionId();
        if (sid == null || sid.isEmpty()) {
            send(ctx, userId, "当前没有可执行的 Claude 会话，先用 /plan on 产出方案。");
            return ModeOutcome.handled();
        }
        boolean admin = isAdmin(userId);
        session.setClaudeApprovedExec(true);
        if (admin) {
            session.setClaudePrivileged(true);
            session.setClaudePlanMode(false);
        }
        send(ctx, userId, "✅ 已批准执行上一轮计划。"
                + (admin ? "（已提权，可写可执行）" : "（非管理员，仅只读操作可执行；写操作需联系管理员）")
                + "\n发送消息即可触发，如：执行");
        return ModeOutcome.handled();
    }

    private boolean isAdmin(String userId) {
        return userId != null && claudeAdminUsers.contains(userId);
    }

    private ModeOutcome handleMode(ModeContext ctx, PlayerSession session, String arg) {
        String userId = session.getUserId();
        if (arg.isEmpty()) {
            send(ctx, userId, "用法：/mode [chat|claude]\n"
                    + "当前模式：" + session.getCurrentMode().name().toLowerCase());
            return ModeOutcome.handled();
        }

        BotModeType target = BotModeType.fromName(arg);
        if (target == null) {
            send(ctx, userId, "未知模式：" + arg + "\n支持的模式：chat, claude");
            return ModeOutcome.handled();
        }
        if (target == BotModeType.FARM) {
            send(ctx, userId, "农场模式通过 # 前缀命令直接使用，无需切换。");
            return ModeOutcome.handled();
        }
        if (target == BotModeType.REVIEW) {
            send(ctx, userId, "点评模式通过上传视频自动触发，无需切换。");
            return ModeOutcome.handled();
        }

        if (!allowModeSwitch(ctx, session, session.getCurrentMode(), target)) {
            return ModeOutcome.handled();
        }
        session.setCurrentMode(target);
        try {
            ctx.sessions().scheduleFlush(userId);
        } catch (Exception e) {
            log.error("保存会话失败, userId={}", userId, e);
        }
        if (target == BotModeType.CLAUDE) {
            // 管理员默认提权（opt-in）：进入模式即切提权档，省去开场 /sudo on。提权为 transient，/sudo off 或重启回收。
            boolean autoPrivileged = adminDefaultPrivileged && isAdmin(userId);
            if (autoPrivileged) {
                session.setClaudePrivileged(true);
                session.setClaudePlanMode(false);   // 互斥：bypass 与 plan 不可并存
            }
            send(ctx, userId, "已切换到 Claude Bridge 模式，发送 /new 开始新会话，或 /sessions 查看历史会话。"
                    + (autoPrivileged ? "\n🔓 已默认开启无限制模式（本会话生效）；用完请及时 /sudo off。" : ""));
        } else {
            send(ctx, userId, "已切换到 " + target.name().toLowerCase() + " 模式");
        }
        return ModeOutcome.handled();
    }

    private ModeOutcome handleNew(ModeContext ctx, PlayerSession session) {
        session.setActiveClaudeSessionId(null);
        send(ctx, session.getUserId(), "已开始新的 Claude 会话。");
        return ModeOutcome.handled();
    }

    private ModeOutcome handleSessions(ModeContext ctx, PlayerSession session) {
        String userId = session.getUserId();
        ClaudeSessionRepository repo = ctx.claudeSessionRepo();
        if (repo == null) {
            send(ctx, userId, "Claude Bridge 未启用。");
            return ModeOutcome.handled();
        }
        List<ClaudeSession> sessions = repo.findByUserIdOrderByUpdatedDesc(userId, SESSIONS_LIST_LIMIT);
        if (sessions.isEmpty()) {
            send(ctx, userId, "暂无历史 Claude 会话，发送消息即可创建。");
            return ModeOutcome.handled();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("最近的 Claude 会话：\n");
        SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm");
        for (int i = 0; i < sessions.size(); i++) {
            ClaudeSession s = sessions.get(i);
            sb.append(i + 1).append(". ")
              .append(titleOrDefault(s.getTitle()))
              .append("（").append(fmt.format(new Date(s.getUpdatedAt()))).append("）\n");
        }
        sb.append("\n发送 /resume <序号> 恢复对应会话。");
        send(ctx, userId, sb.toString());
        return ModeOutcome.handled();
    }

    private ModeOutcome handleResume(ModeContext ctx, PlayerSession session, String arg) {
        String userId = session.getUserId();
        ClaudeSessionRepository repo = ctx.claudeSessionRepo();
        if (repo == null) {
            send(ctx, userId, "Claude Bridge 未启用。");
            return ModeOutcome.handled();
        }
        if (arg.isEmpty()) {
            send(ctx, userId, "用法：/resume <序号|会话ID>");
            return ModeOutcome.handled();
        }
        ClaudeSession target = resolveSession(repo, userId, arg);
        if (target == null) {
            send(ctx, userId, "未找到对应会话：" + arg + "\n发送 /sessions 查看可恢复的会话。");
            return ModeOutcome.handled();
        }
        session.setActiveClaudeSessionId(target.getSessionId());
        if (session.getCurrentMode() != BotModeType.CLAUDE) {
            if (!allowModeSwitch(ctx, session, session.getCurrentMode(), BotModeType.CLAUDE)) {
                return ModeOutcome.handled();
            }
            session.setCurrentMode(BotModeType.CLAUDE);
            try {
                ctx.sessions().scheduleFlush(userId);
            } catch (Exception e) {
                log.error("保存会话失败, userId={}", userId, e);
            }
        }
        send(ctx, userId, "已恢复 Claude 会话：" + titleOrDefault(target.getTitle()));
        return ModeOutcome.handled();
    }

    private ClaudeSession resolveSession(ClaudeSessionRepository repo, String userId, String arg) {
        Integer index = parseIndex(arg);
        if (index != null) {
            List<ClaudeSession> sessions = repo.findByUserIdOrderByUpdatedDesc(userId, SESSIONS_LIST_LIMIT);
            if (index >= 1 && index <= sessions.size()) {
                return sessions.get(index - 1);
            }
            return null;
        }
        ClaudeSession byId = repo.findById(arg);
        if (byId != null && userId.equals(byId.getUserId())) {
            return byId;
        }
        return null;
    }

    private static Integer parseIndex(String arg) {
        try {
            return Integer.valueOf(arg.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String titleOrDefault(String title) {
        return (title == null || title.isEmpty()) ? "(无标题)" : title;
    }

    /**
     * 触发 {@link HookEvent#ON_MODE_SWITCH} hook（from→to）。
     * 返回 true=允许切换，false=被阻断（已发提示）。默认无 hook 即放行，行为不变。
     */
    private boolean allowModeSwitch(ModeContext ctx, PlayerSession session, BotModeType from, BotModeType to) {
        if (!ctx.hooks().has(HookEvent.ON_MODE_SWITCH)) {
            return true;
        }
        HookVerdict verdict = ctx.hooks().fire(HookEvent.ON_MODE_SWITCH,
                HookContext.builder().userId(session.getUserId()).session(session)
                        .fromMode(from).toMode(to).build());
        if (verdict.isBlock()) {
            send(ctx, session.getUserId(), verdict.getReason() == null ? "模式切换被拒绝" : verdict.getReason());
            return false;
        }
        return true;
    }

    private void sendHelp(ModeContext ctx, String userId) {
        StringBuilder sb = new StringBuilder();
        sb.append("ilink-bot 系统命令\n");
        sb.append("/mode [chat|claude] — 切换当前模式\n");
        sb.append("/status — 查看当前模式和状态\n");
        sb.append("/new — 开始新的 Claude 会话\n");
        sb.append("/sessions — 列出历史 Claude 会话\n");
        sb.append("/resume <序号> — 恢复指定 Claude 会话\n");
        sb.append("/help — 显示此帮助\n");
        sb.append("/plan [on|off] — Claude 计划模式（只读产出方案），/approve 后执行\n");
        sb.append("/approve — 批准执行上一轮计划（管理员可完整写/执行）\n");
        if (isAdmin(userId)) {
            sb.append("/sudo [on|off|status] — 管理员提权 Claude 至无限制模式（管控本机）\n");
        }
        sb.append("\n# 前缀走农场游戏命令（#帮助 查看游戏菜单）");
        send(ctx, userId, sb.toString());
    }

    private void sendStatus(ModeContext ctx, PlayerSession session) {
        StringBuilder sb = new StringBuilder();
        sb.append("当前模式：").append(session.getCurrentMode().name().toLowerCase()).append("\n");
        sb.append("玩家：Lv.").append(session.getLevel())
          .append("（").append(session.getExp()).append("/").append(session.getLevel() * 100).append(" EXP）\n");
        sb.append("金币：").append(session.getGold());
        send(ctx, session.getUserId(), sb.toString());
    }

    private void sendUnknown(ModeContext ctx, String userId, String cmd) {
        send(ctx, userId, "未知命令：/" + cmd + "\n输入 /help 查看可用命令");
    }

    private void send(ModeContext ctx, String userId, String text) {
        try {
            ctx.sender().sendText(userId, text);
        } catch (Exception e) {
            log.error("系统命令发送消息失败, userId={}", userId, e);
        }
    }
}

