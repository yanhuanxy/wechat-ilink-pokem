package com.github.wechat.ilink.bot.session;

import com.github.wechat.ilink.bot.farm.model.FarmPlot;
import com.github.wechat.ilink.bot.farm.model.Inventory;
import com.github.wechat.ilink.bot.mode.BotModeType;

import java.util.ArrayList;
import java.util.List;

public class PlayerSession {

    private final String userId;
    private int gold;
    private int exp;
    private int level;
    private int maxPlots;
    private int coupon;
    private String nickname;
    private String lastCheckin;
    private int checkinStreak;
    private List<FarmPlot> plots;
    private Inventory inventory;
    private long lastActivity;
    private boolean dirty;
    private BotModeType currentMode;
    private transient String activeClaudeSessionId;
    // Claude Bridge 提权标志：管理员经 /sudo 置位后，本会话的 claude 子进程以无限制模式运行。
    // transient：不入库，重启自动回到默认受限。
    private transient boolean claudePrivileged;
    // Claude Bridge plan 档标志：/plan on 置位后，子进程以 --permission-mode plan 只读产出方案。
    // transient：不入库，重启回收；与 claudePrivileged 互斥（/sudo on 或 /approve 时清零）。
    private transient boolean claudePlanMode;
    // Claude Bridge 执行闭环标志：/approve 置位，下一条消息消费（拼"执行上一轮计划"前缀）后清除。
    // transient：不入库，重启回收；切换会话（setActiveClaudeSessionId）时清除（上一轮计划语义失效）。
    private transient boolean claudeApprovedExec;
    // 当前活跃 Claude 会话累计对话轮次：达阈值时触发自动 /compact。
    // transient：不入库，重启回零；切换会话（/new、/resume、新会话）时归零。
    private transient int claudeTurnCount;

    // 最近一次购买/种植的作物 key：#种植 / #施肥 无参时复用，省去重复输入作物名。
    // transient：不入库，重启清空（先验证价值，确认有用再持久化到 player 表）。
    private transient String lastCropKey;

    public PlayerSession(String userId) {
        this.userId = userId;
        this.gold = 500;
        this.exp = 0;
        this.level = 1;
        this.maxPlots = 4;
        this.coupon = 0;
        this.checkinStreak = 0;
        this.plots = new ArrayList<FarmPlot>();
        for (int i = 0; i < 36; i++) {
            plots.add(new FarmPlot(i));
        }
        this.inventory = new Inventory();
        this.lastActivity = System.currentTimeMillis();
        this.currentMode = BotModeType.defaultMode();
        this.dirty = true;
    }

    public String getUserId() {
        return userId;
    }

    public int getGold() {
        return gold;
    }

    public void setGold(int gold) {
        this.gold = gold;
        this.dirty = true;
    }

    public int getExp() {
        return exp;
    }

    public void setExp(int exp) {
        this.exp = exp;
        this.dirty = true;
    }

    public void addExp(int amount) {
        this.exp += amount;
        checkLevelUp();
        this.dirty = true;
    }

    public int getLevel() {
        return level;
    }

    public void setLevel(int level) {
        this.level = level;
        this.dirty = true;
    }

    public int getMaxPlots() {
        return maxPlots;
    }

    public void setMaxPlots(int maxPlots) {
        this.maxPlots = maxPlots;
        this.dirty = true;
    }

    public int getCoupon() {
        return coupon;
    }

    public void setCoupon(int coupon) {
        this.coupon = coupon;
        this.dirty = true;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
        this.dirty = true;
    }

    public String getLastCheckin() {
        return lastCheckin;
    }

    public void setLastCheckin(String lastCheckin) {
        this.lastCheckin = lastCheckin;
        this.dirty = true;
    }

    public int getCheckinStreak() {
        return checkinStreak;
    }

    public void setCheckinStreak(int checkinStreak) {
        this.checkinStreak = checkinStreak;
        this.dirty = true;
    }

    public List<FarmPlot> getPlots() {
        return plots;
    }

    public void setPlots(List<FarmPlot> plots) {
        this.plots = plots;
        this.dirty = true;
    }

    public FarmPlot getPlot(int index) {
        if (index < 0 || index >= plots.size()) {
            return null;
        }
        return plots.get(index);
    }

    public List<FarmPlot> getActivePlots() {
        List<FarmPlot> active = new ArrayList<FarmPlot>();
        for (int i = 0; i < maxPlots && i < plots.size(); i++) {
            active.add(plots.get(i));
        }
        return active;
    }

    public Inventory getInventory() {
        return inventory;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
        this.dirty = true;
    }

    public long getLastActivity() {
        return lastActivity;
    }

    public void touchActivity() {
        this.lastActivity = System.currentTimeMillis();
    }

    public boolean isDirty() {
        return dirty;
    }

    public void clearDirty() {
        this.dirty = false;
    }

    /** 直接改内存态（如偷菜给贼加产量）后无字段 setter 触发 dirty 时，显式标脏以确保刷盘。 */
    public void markDirty() {
        this.dirty = true;
    }

    public BotModeType getCurrentMode() {
        return currentMode == null ? BotModeType.defaultMode() : currentMode;
    }

    public void setCurrentMode(BotModeType currentMode) {
        if (currentMode == null) {
            currentMode = BotModeType.defaultMode();
        }
        this.currentMode = currentMode;
        this.dirty = true;
    }

    public String getActiveClaudeSessionId() {
        return activeClaudeSessionId;
    }

    public void setActiveClaudeSessionId(String activeClaudeSessionId) {
        // 切换到不同会话（含 /new 置 null、/resume 切换、新会话首次赋 id）时轮次归零；同 id 续传不重置。
        boolean changed = (activeClaudeSessionId == null)
                ? (this.activeClaudeSessionId != null)
                : !activeClaudeSessionId.equals(this.activeClaudeSessionId);
        if (changed) {
            this.claudeTurnCount = 0;
            this.claudeApprovedExec = false;   // 切会话后"上一轮计划"失效，避免带到新会话执行
        }
        this.activeClaudeSessionId = activeClaudeSessionId;
    }

    public boolean isClaudePrivileged() {
        return claudePrivileged;
    }

    public void setClaudePrivileged(boolean claudePrivileged) {
        this.claudePrivileged = claudePrivileged;
    }

    public boolean isClaudePlanMode() {
        return claudePlanMode;
    }

    public void setClaudePlanMode(boolean claudePlanMode) {
        this.claudePlanMode = claudePlanMode;
    }

    public boolean isClaudeApprovedExec() {
        return claudeApprovedExec;
    }

    public void setClaudeApprovedExec(boolean claudeApprovedExec) {
        this.claudeApprovedExec = claudeApprovedExec;
    }

    public int getClaudeTurnCount() {
        return claudeTurnCount;
    }

    public int incrementClaudeTurn() {
        return ++claudeTurnCount;
    }

    public void resetClaudeTurnCount() {
        this.claudeTurnCount = 0;
    }

    public String getLastCropKey() {
        return lastCropKey;
    }

    public void setLastCropKey(String lastCropKey) {
        this.lastCropKey = lastCropKey;
    }

    public boolean spendGold(int amount) {
        if (gold < amount) {
            return false;
        }
        gold -= amount;
        dirty = true;
        return true;
    }

    public void addGold(int amount) {
        gold += amount;
        dirty = true;
    }

    private void checkLevelUp() {
        int threshold = level * 100;
        while (exp >= threshold) {
            exp -= threshold;
            level++;
            if (level % 3 == 0 && maxPlots < 36) {
                maxPlots += 2;
            }
            threshold = level * 100;
        }
    }
}
