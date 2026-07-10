package com.github.wechat.ilink.bot.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.ilink.bot.util.AppPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class TaskConfig {

    private static final Logger log = LoggerFactory.getLogger(TaskConfig.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final long DEFAULT_MAX_VIDEO_BYTES = 50L * 1024 * 1024;
    private static final long DEFAULT_TIMEOUT_MS = 600_000L;
    private static final long DEFAULT_BUFFER_TTL_MS = 60_000L;

    private boolean enabled;
    private String claudePath;
    private String workspaceRoot;
    private String claudeHome;
    // 视频点评专用模型（值由 GameApplication 从 models-config.json 注入）
    private String videoReviewModel;
    // Claude Bridge 专用模型（值由 GameApplication 从 models-config.json 注入）
    private String claudeBridgeModel;
    // Claude Bridge 小快模型（haiku 角色；为空时复用 claudeBridgeModel）
    private String claudeBridgeSmallModel;
    // Claude Bridge 子进程的 Anthropic 协议端点 / 凭证（按 bridge.provider 解析；为空时回退到 dashscope*）
    private String claudeBridgeBaseUrl;
    private String claudeBridgeApiKey;
    // 共享 DashScope provider：endpoint / 凭证 / 视频上传 URL（值由 GameApplication 注入）
    private String dashscopeBaseUrl;
    private String dashscopeApiKey;
    private String dashscopeUploadsUrl;
    private long timeoutMs;
    private String permissionMode;
    private List<String> allowedTools;
    private List<String> disallowedTools;
    private boolean streamingEnabled;
    private long maxVideoBytes;
    private long bufferTtlMs;
    private boolean claudeBridgeEnabled;
    private String claudeBridgeCwd;
    private int claudeBridgeMaxHistorySessions;
    // Claude Bridge 受限（默认）权限档：headless default 模式 + 只读工具白名单 + 写/执行黑名单，
    // 子进程 cwd 为每用户工作目录、无 --add-dir → Claude 只能读自己工作目录内的文件。
    private String claudeBridgePermissionMode;
    private List<String> claudeBridgeAllowedTools;
    private List<String> claudeBridgeDisallowedTools;
    // Claude Bridge plan 档：/plan 切换，--permission-mode plan，只读探索产出方案文本；复用只读白名单、不下黑名单。
    private String claudeBridgePlanMode;
    // Claude Bridge 提权档：管理员经 /sudo 切换到此模式，可无限制管控宿主机。
    private String claudeBridgePrivilegedMode;
    // 可执行 /sudo 的微信 userId 白名单（连接用户默认无提权能力）。
    private List<String> claudeAdminUsers;
    // 自动压缩阈值：0=关闭；>0 表示每会话累计 N 轮对话后，下一条消息前自动跑一次 /compact 压缩上下文。
    private int claudeBridgeCompactThreshold;
    // 管理员默认提权：true 时白名单内 admin 进入 CLAUDE 模式即默认提权档，省去开场 /sudo on。
    // 默认 false（对现状零影响）；提权仍为 transient，/sudo off 或重启回收。
    private boolean claudeBridgeAdminDefaultPrivileged;

    public TaskConfig() {
        this.enabled = false;
        this.claudePath = "claude";
        this.workspaceRoot = AppPaths.data("tasks");
        this.claudeHome = AppPaths.data("claude-home");
        this.videoReviewModel = "";
        this.claudeBridgeModel = "";
        this.claudeBridgeSmallModel = "";
        this.claudeBridgeBaseUrl = "";
        this.claudeBridgeApiKey = "";
        this.dashscopeBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        this.dashscopeApiKey = "";
        this.dashscopeUploadsUrl = "https://dashscope.aliyuncs.com/api/v1/uploads";
        this.timeoutMs = DEFAULT_TIMEOUT_MS;
        this.permissionMode = "bypassPermissions";
        this.allowedTools = new ArrayList<String>();
        this.disallowedTools = new ArrayList<String>();
        this.streamingEnabled = true;
        this.maxVideoBytes = DEFAULT_MAX_VIDEO_BYTES;
        this.bufferTtlMs = DEFAULT_BUFFER_TTL_MS;
        this.claudeBridgeEnabled = false;
        this.claudeBridgeCwd = AppPaths.data("claude-workspace");
        this.claudeBridgeMaxHistorySessions = 50;
        this.claudeBridgePermissionMode = "default";
        this.claudeBridgeAllowedTools = new ArrayList<String>();
        this.claudeBridgeAllowedTools.add("Read");
        this.claudeBridgeAllowedTools.add("LS");
        this.claudeBridgeAllowedTools.add("Glob");
        this.claudeBridgeAllowedTools.add("Grep");
        this.claudeBridgeDisallowedTools = new ArrayList<String>();
        this.claudeBridgeDisallowedTools.add("Bash");
        this.claudeBridgeDisallowedTools.add("Write");
        this.claudeBridgeDisallowedTools.add("Edit");
        this.claudeBridgeDisallowedTools.add("NotebookEdit");
        this.claudeBridgePlanMode = "plan";
        this.claudeBridgePrivilegedMode = "bypassPermissions";
        this.claudeAdminUsers = new ArrayList<String>();
        this.claudeBridgeCompactThreshold = 0;
        this.claudeBridgeAdminDefaultPrivileged = false;
    }

    public static TaskConfig load(String filePath) {
        File file = new File(filePath);
        if (!file.exists()) {
            createTemplate(file);
            TaskConfig config = new TaskConfig();
            log.info("Task 未启用：{} 未找到，已生成模板", filePath);
            return config;
        }

        try {
            TaskConfig config = MAPPER.readValue(file, TaskConfig.class);
            migrateLegacyFields(config);
            // 模型/endpoint/token 不再来自本文件，由 GameApplication 从 models-config.json 注入后单独记录
            log.info("Task 配置已加载: enabled={}, claudePath={}, workspaceRoot={}, claudeHome={}, permissionMode={}",
                    config.isEnabled(), config.getClaudePath(), config.getWorkspaceRoot(),
                    config.getClaudeHome(), config.getPermissionMode());
            return config;
        } catch (IOException e) {
            log.error("Task 配置文件读取失败: {}", filePath, e);
            return new TaskConfig();
        }
    }

    /**
     * 向后兼容迁移：旧配置文件若存在 'model' 字段，迁移到 'videoReviewModel'。
     * 删除的冗余字段（haikuModel/sonnetModel/opusModel/subagentModel）忽略。
     */
    private static void migrateLegacyFields(TaskConfig config) {
        // Jackson 会忽略未知字段，所以旧配置的 haikuModel 等不会报错，直接丢弃
        // 若需主动迁移，可在 JsonNode 层处理，此处简化为依赖 Jackson 默认行为
    }

    private static void createTemplate(File file) {
        try {
            file.getParentFile().mkdirs();
            TaskConfig template = new TaskConfig();
            template.setEnabled(true);

            FileWriter writer = new FileWriter(file);
            writer.write(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(template));
            writer.close();
            log.info("已创建 Task 配置模板: {}", file.getAbsolutePath());
        } catch (IOException e) {
            log.warn("无法创建 Task 配置模板", e);
        }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getClaudePath() { return claudePath; }
    public void setClaudePath(String claudePath) { this.claudePath = claudePath; }
    public String getWorkspaceRoot() { return workspaceRoot; }
    public void setWorkspaceRoot(String workspaceRoot) { this.workspaceRoot = workspaceRoot; }
    public String getClaudeHome() { return claudeHome; }
    public void setClaudeHome(String claudeHome) { this.claudeHome = claudeHome; }
    public String getVideoReviewModel() { return videoReviewModel; }
    public void setVideoReviewModel(String videoReviewModel) { this.videoReviewModel = videoReviewModel; }
    public String getClaudeBridgeModel() { return claudeBridgeModel; }
    public void setClaudeBridgeModel(String claudeBridgeModel) { this.claudeBridgeModel = claudeBridgeModel; }
    public String getClaudeBridgeSmallModel() { return claudeBridgeSmallModel; }
    public void setClaudeBridgeSmallModel(String claudeBridgeSmallModel) { this.claudeBridgeSmallModel = claudeBridgeSmallModel; }
    public String getClaudeBridgeBaseUrl() { return claudeBridgeBaseUrl; }
    public void setClaudeBridgeBaseUrl(String claudeBridgeBaseUrl) { this.claudeBridgeBaseUrl = claudeBridgeBaseUrl; }
    public String getClaudeBridgeApiKey() { return claudeBridgeApiKey; }
    public void setClaudeBridgeApiKey(String claudeBridgeApiKey) { this.claudeBridgeApiKey = claudeBridgeApiKey; }
    public String getDashscopeBaseUrl() { return dashscopeBaseUrl; }
    public void setDashscopeBaseUrl(String dashscopeBaseUrl) { this.dashscopeBaseUrl = dashscopeBaseUrl; }
    public String getDashscopeApiKey() { return dashscopeApiKey; }
    public void setDashscopeApiKey(String dashscopeApiKey) { this.dashscopeApiKey = dashscopeApiKey; }
    public String getDashscopeUploadsUrl() { return dashscopeUploadsUrl; }
    public void setDashscopeUploadsUrl(String dashscopeUploadsUrl) { this.dashscopeUploadsUrl = dashscopeUploadsUrl; }
    public long getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; }
    public String getPermissionMode() { return permissionMode; }
    public void setPermissionMode(String permissionMode) { this.permissionMode = permissionMode; }
    public List<String> getAllowedTools() { return allowedTools; }
    public void setAllowedTools(List<String> allowedTools) { this.allowedTools = allowedTools; }
    public List<String> getDisallowedTools() { return disallowedTools; }
    public void setDisallowedTools(List<String> disallowedTools) { this.disallowedTools = disallowedTools; }
    public boolean isStreamingEnabled() { return streamingEnabled; }
    public void setStreamingEnabled(boolean streamingEnabled) { this.streamingEnabled = streamingEnabled; }
    public long getMaxVideoBytes() { return maxVideoBytes; }
    public void setMaxVideoBytes(long maxVideoBytes) { this.maxVideoBytes = maxVideoBytes; }
    public long getBufferTtlMs() { return bufferTtlMs; }
    public void setBufferTtlMs(long bufferTtlMs) { this.bufferTtlMs = bufferTtlMs; }
    public boolean isClaudeBridgeEnabled() { return claudeBridgeEnabled; }
    public void setClaudeBridgeEnabled(boolean claudeBridgeEnabled) { this.claudeBridgeEnabled = claudeBridgeEnabled; }
    public String getClaudeBridgeCwd() { return claudeBridgeCwd; }
    public void setClaudeBridgeCwd(String claudeBridgeCwd) { this.claudeBridgeCwd = claudeBridgeCwd; }
    public int getClaudeBridgeMaxHistorySessions() { return claudeBridgeMaxHistorySessions; }
    public void setClaudeBridgeMaxHistorySessions(int claudeBridgeMaxHistorySessions) { this.claudeBridgeMaxHistorySessions = claudeBridgeMaxHistorySessions; }
    public String getClaudeBridgePermissionMode() { return claudeBridgePermissionMode; }
    public void setClaudeBridgePermissionMode(String claudeBridgePermissionMode) { this.claudeBridgePermissionMode = claudeBridgePermissionMode; }
    public List<String> getClaudeBridgeAllowedTools() { return claudeBridgeAllowedTools; }
    public void setClaudeBridgeAllowedTools(List<String> claudeBridgeAllowedTools) { this.claudeBridgeAllowedTools = claudeBridgeAllowedTools; }
    public List<String> getClaudeBridgeDisallowedTools() { return claudeBridgeDisallowedTools; }
    public void setClaudeBridgeDisallowedTools(List<String> claudeBridgeDisallowedTools) { this.claudeBridgeDisallowedTools = claudeBridgeDisallowedTools; }
    public String getClaudeBridgePlanMode() { return claudeBridgePlanMode; }
    public void setClaudeBridgePlanMode(String claudeBridgePlanMode) { this.claudeBridgePlanMode = claudeBridgePlanMode; }
    public String getClaudeBridgePrivilegedMode() { return claudeBridgePrivilegedMode; }
    public void setClaudeBridgePrivilegedMode(String claudeBridgePrivilegedMode) { this.claudeBridgePrivilegedMode = claudeBridgePrivilegedMode; }
    public List<String> getClaudeAdminUsers() { return claudeAdminUsers; }
    public void setClaudeAdminUsers(List<String> claudeAdminUsers) { this.claudeAdminUsers = claudeAdminUsers; }
    public int getClaudeBridgeCompactThreshold() { return claudeBridgeCompactThreshold; }
    public void setClaudeBridgeCompactThreshold(int claudeBridgeCompactThreshold) { this.claudeBridgeCompactThreshold = claudeBridgeCompactThreshold; }
    public boolean isClaudeBridgeAdminDefaultPrivileged() { return claudeBridgeAdminDefaultPrivileged; }
    public void setClaudeBridgeAdminDefaultPrivileged(boolean claudeBridgeAdminDefaultPrivileged) { this.claudeBridgeAdminDefaultPrivileged = claudeBridgeAdminDefaultPrivileged; }
}
