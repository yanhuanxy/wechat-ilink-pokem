package com.github.wechat.ilink.bot.mode.claude;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.ilink.bot.config.TaskConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Session-aware Claude Code 子进程适配器（Claude Bridge 模式专用）。
 * 支持 --resume 跨消息续传，并在 init 事件回传 session_id。
 */
public class ClaudeCodeAdapter {

    private static final Logger log = LoggerFactory.getLogger(ClaudeCodeAdapter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int PREVIEW_LEN = 200;

    private final TaskConfig config;
    private final String cwd;
    private final String model;

    public ClaudeCodeAdapter(TaskConfig config) {
        this.config = config;
        this.cwd = config.getClaudeBridgeCwd();
        this.model = config.getClaudeBridgeModel();
        if (!isSet(this.model)) {
            throw new IllegalStateException("Claude Bridge 启用但 claudeBridgeModel 未配置，请在 models-config.json 的 bridge.model 中配置");
        }
    }

    public ClaudeCodeAdapter(TaskConfig config, String cwd, String model) {
        this.config = config;
        this.cwd = cwd;
        this.model = model;
    }

    public void run(String userId, String prompt, String resumeSessionId, boolean privileged, boolean plan,
                    ClaudeAdapterCallback callback) {
        long start = System.currentTimeMillis();
        Process process = null;
        StreamConsumer stdout = null;
        StreamConsumer stderr = null;
        try {
            Path workDir = Paths.get(cwd, BridgeWorkspace.sanitize(userId));
            Files.createDirectories(workDir);
            ensureClaudeHome();

            process = startProcess(prompt, resumeSessionId, workDir, privileged, plan);
            stdout = new StreamConsumer(process.getInputStream(), callback, true);
            stderr = new StreamConsumer(process.getErrorStream(), callback, false);
            stdout.start();
            stderr.start();

            log.info("claude bridge 子进程已启动: userId={}, resume={}, privileged={}, plan={}, cwd={}, model={}, promptPreview=[{}]",
                    userId, resumeSessionId != null, privileged, plan, workDir, model, preview(prompt));

            boolean finished = process.waitFor(config.getTimeoutMs(), TimeUnit.MILLISECONDS);
            if (!finished) {
                destroyForcibly(process);
                log.warn("claude bridge 子进程超时被强杀: userId={}, timeoutMs={}", userId, config.getTimeoutMs());
                callback.onError(new RuntimeException("任务超时 (" + config.getTimeoutMs() / 1000 + "s)"));
                return;
            }

            stdout.join(2000);
            stderr.join(2000);

            int exitCode = process.exitValue();
            long elapsed = System.currentTimeMillis() - start;
            log.info("claude bridge 子进程结束: userId={}, exitCode={}, elapsedMs={}", userId, exitCode, elapsed);

            String apiError = stdout.getApiError();
            if (apiError != null) {
                log.error("claude bridge 返回 API 错误: userId={}, error={}", userId, apiError);
                callback.onError(new RuntimeException("模型 API 错误: " + apiError));
                return;
            }
            if (exitCode != 0) {
                callback.onError(new RuntimeException("claude 退出码 " + exitCode + ", stderr=" + stderr.getCollected()));
                return;
            }

            String fullText = stdout.getFullText().trim();
            callback.onComplete(fullText);
        } catch (Exception e) {
            log.error("claude bridge 任务执行失败: userId={}", userId, e);
            callback.onError(e);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    protected Process startProcess(String prompt, String resumeSessionId, Path workDir, boolean privileged, boolean plan) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(buildArgs(prompt, resumeSessionId, privileged, plan));
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(false);
        Map<String, String> env = pb.environment();
        applyBridgeEnv(env, config);
        logBridgeEnv(env);
        return pb.start();
    }

    /**
     * Bridge 子进程环境变量。下发完整一套有效模型参数——claude 内部按 haiku/sonnet/opus/reasoning 角色选模型，
     * 缺失的角色会回落到内置 Claude 模型名（第三方端点不存在 → {@code 400 [1211] 模型不存在}）。
     * 故先清掉所有继承的 {@code ANTHROPIC_*}，再由 {@code bridge.model}/{@code bridge.smallModel} 派生完整一套，
     * 配 {@code /apps/anthropic} 网关要求的 Bearer 认证与 flags。
     */
    static void applyBridgeEnv(Map<String, String> env, TaskConfig config) {
        // 1. 清理所有继承的 ANTHROPIC_*（启动环境理论干净，防御性清零保证确定性）
        for (String key : new String[]{
                "ANTHROPIC_MODEL", "ANTHROPIC_SMALL_FAST_MODEL",
                "ANTHROPIC_DEFAULT_HAIKU_MODEL", "ANTHROPIC_DEFAULT_SONNET_MODEL",
                "ANTHROPIC_DEFAULT_OPUS_MODEL", "ANTHROPIC_REASONING_MODEL",
                "ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_API_KEY", "ANTHROPIC_BASE_URL"}) {
            env.remove(key);
        }
        // 2. 由 bridge.model / smallModel 派生完整一套（haiku 用 small，其余用 model）
        String model = config.getClaudeBridgeModel();
        String small = isSet(config.getClaudeBridgeSmallModel()) ? config.getClaudeBridgeSmallModel() : model;
        if (isSet(model)) {
            env.put("ANTHROPIC_MODEL", model);
            env.put("ANTHROPIC_SMALL_FAST_MODEL", small);
            env.put("ANTHROPIC_DEFAULT_HAIKU_MODEL", small);
            env.put("ANTHROPIC_DEFAULT_SONNET_MODEL", model);
            env.put("ANTHROPIC_DEFAULT_OPUS_MODEL", model);
            env.put("ANTHROPIC_REASONING_MODEL", model);
        }
        // 3. Anthropic 端点 + Bearer 认证（bridge.provider 解析；未配置回退 dashscope*）
        String baseUrl = isSet(config.getClaudeBridgeBaseUrl())
                ? config.getClaudeBridgeBaseUrl() : config.getDashscopeBaseUrl();
        if (isSet(baseUrl)) {
            env.put("ANTHROPIC_BASE_URL", baseUrl);
        }
        String authToken = isSet(config.getClaudeBridgeApiKey())
                ? config.getClaudeBridgeApiKey() : config.getDashscopeApiKey();
        if (isSet(authToken)) {
            env.put("ANTHROPIC_AUTH_TOKEN", authToken);
        }
        // 4. 第三方兼容端点关闭 Anthropic 专有特性
        env.put("CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS", "1");
        env.put("ENABLE_TOOL_SEARCH", "0");
        // 5. 隔离子进程：独立 CLAUDE_CONFIG_DIR，避免继承用户 ~/.claude/settings.json 干扰（实测必需，
        //    否则 claude 读到宿主 settings.json 与本配置冲突 → 400 [1211]）
        if (isSet(config.getClaudeHome())) {
            // 下发绝对路径：子进程 CWD 为 <claudeBridgeCwd>/<userId>，相对的 CLAUDE_CONFIG_DIR 会被
            // claude 按自身 CWD 二次解析到不存在位置 → 隔离配置目录与已装 skill 全部失效（P0 实测缺陷 B）。
            env.put("CLAUDE_CONFIG_DIR",
                    Paths.get(config.getClaudeHome()).toAbsolutePath().toString());
        }
    }

    /** 打印下发的完整模型参数（脱敏：不打印 token 值，auth 只打布尔），便于排查 1211。 */
    private static void logBridgeEnv(Map<String, String> env) {
        log.info("claude bridge 子进程模型环境: model={}, small={}, haiku={}, sonnet={}, opus={}, reasoning={}, "
                        + "baseUrl={}, authSet={}, disableBetas={}, toolSearch={}, claudeConfigDir={}",
                env.get("ANTHROPIC_MODEL"), env.get("ANTHROPIC_SMALL_FAST_MODEL"),
                env.get("ANTHROPIC_DEFAULT_HAIKU_MODEL"), env.get("ANTHROPIC_DEFAULT_SONNET_MODEL"),
                env.get("ANTHROPIC_DEFAULT_OPUS_MODEL"), env.get("ANTHROPIC_REASONING_MODEL"),
                env.get("ANTHROPIC_BASE_URL"), env.get("ANTHROPIC_AUTH_TOKEN") != null,
                env.get("CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS"), env.get("ENABLE_TOOL_SEARCH"),
                env.get("CLAUDE_CONFIG_DIR"));
    }

    List<String> buildArgs(String prompt, String resumeSessionId, boolean privileged, boolean plan) {
        List<String> args = new ArrayList<String>();
        args.add(config.getClaudePath());
        args.add("-p");
        args.add(prompt);
        args.add("--output-format");
        args.add("stream-json");
        args.add("--verbose");
        if (model != null && !model.isEmpty()) {
            args.add("--model");
            args.add(model);
        }
        if (privileged) {
            // 提权档：管理员经 /sudo 或 /approve 切换，bypass 忽略工具策略，故只下 permission-mode。
            // headless -p 下 --permission-mode bypassPermissions 单独不够：bypass 仍要一次危险模式确认，
            // 非交互无法预接受 → 子进程实际跑在受限等效档、Write/Edit/Bash 被收走（P0 实测根因）。
            // 需 --dangerously-skip-permissions 跳过确认，等价于宿主 ~/.claude 的 skipDangerousModePermissionPrompt。
            args.add("--permission-mode");
            args.add(config.getClaudeBridgePrivilegedMode());
            args.add("--dangerously-skip-permissions");
        } else if (plan) {
            // plan 档：--permission-mode plan，只读探索产出方案文本；复用只读白名单，不下黑名单（plan 模式本身禁写）。
            args.add("--permission-mode");
            args.add(config.getClaudeBridgePlanMode());
            appendBridgePolicy(args, config.getClaudeBridgeAllowedTools(), null);
        } else {
            // 受限档（默认）：headless default 模式 + 只读白名单 + 写/执行黑名单。
            // 未在白名单的工具（如 Bash/Write）在 headless 下自动拒绝，工作目录之外无 --add-dir 不可达。
            args.add("--permission-mode");
            args.add(config.getClaudeBridgePermissionMode());
            appendBridgePolicy(args,
                    config.getClaudeBridgeAllowedTools(), config.getClaudeBridgeDisallowedTools());
        }
        if (resumeSessionId != null && !resumeSessionId.isEmpty()) {
            args.add("--resume");
            args.add(resumeSessionId);
        }
        return args;
    }

    /** 追加 Bridge 专用工具策略（--allowedTools / --disallowedTools），空列表不追加。 */
    private static void appendBridgePolicy(List<String> args, List<String> allowed, List<String> disallowed) {
        String allow = joinTools(allowed);
        if (allow != null) {
            args.add("--allowedTools");
            args.add(allow);
        }
        String disallow = joinTools(disallowed);
        if (disallow != null) {
            args.add("--disallowedTools");
            args.add(disallow);
        }
    }

    private static String joinTools(List<String> tools) {
        if (tools == null || tools.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String tool : tools) {
            if (tool == null || tool.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(tool);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private void ensureClaudeHome() throws IOException {
        Files.createDirectories(Paths.get(config.getClaudeHome()));
    }

    private void destroyForcibly(Process p) {
        try {
            p.destroyForcibly().waitFor(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean isSet(String value) {
        return value != null && !value.isEmpty();
    }

    private static String preview(String prompt) {
        if (prompt == null) return "";
        String oneLine = prompt.replace('\n', ' ').replace('\r', ' ');
        return oneLine.length() > PREVIEW_LEN ? oneLine.substring(0, PREVIEW_LEN) + "..." : oneLine;
    }

    static class StreamConsumer extends Thread {

        private final InputStream in;
        private final ClaudeAdapterCallback callback;
        private final boolean parseJson;
        private final StringBuilder collected = new StringBuilder();
        private final StringBuilder streamedText = new StringBuilder();
        private volatile String resultText;
        private volatile boolean sessionIdReported;
        private volatile String apiError;

        StreamConsumer(InputStream in, ClaudeAdapterCallback callback, boolean parseJson) {
            this.in = in;
            this.callback = callback;
            this.parseJson = parseJson;
            setDaemon(true);
        }

        @Override
        public void run() {
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    collected.append(line).append("\n");
                    if (!parseJson) {
                        continue;
                    }
                    handleLine(line);
                }
            } catch (IOException e) {
                // stream closed by process termination
            }
        }

        private void handleLine(String line) {
            JsonNode node;
            try {
                node = MAPPER.readTree(line);
            } catch (Exception ignored) {
                return;
            }
            reportSessionId(node);
            captureApiError(node);
            captureResult(node);
            String text = extractStreamingText(node);
            if (text != null && !text.isEmpty()) {
                streamedText.append(text);
                callback.onToken(text);
            }
        }

        private void captureResult(JsonNode node) {
            JsonNode type = node.get("type");
            if (type == null || !"result".equals(type.asText())) {
                return;
            }
            JsonNode result = node.get("result");
            if (result != null && result.isTextual()) {
                resultText = result.asText();
            }
        }

        private void reportSessionId(JsonNode node) {
            if (sessionIdReported) {
                return;
            }
            JsonNode sid = node.get("session_id");
            if (sid != null && sid.isTextual() && !sid.asText().isEmpty()) {
                sessionIdReported = true;
                callback.onSessionId(sid.asText());
            }
        }

        private void captureApiError(JsonNode node) {
            if (apiError != null) {
                return;
            }
            JsonNode isError = node.get("is_error");
            if (isError != null && isError.asBoolean(false)) {
                JsonNode result = node.get("result");
                apiError = (result != null && result.isTextual()) ? result.asText() : node.toString();
            }
        }

        private String extractStreamingText(JsonNode node) {
            JsonNode delta = node.get("content_block_delta");
            if (delta != null && delta.has("text")) {
                return delta.get("text").asText();
            }
            JsonNode message = node.get("message");
            if (message != null && message.has("content")) {
                return extractContentArray(message.get("content"));
            }
            return null;
        }

        private String extractContentArray(JsonNode content) {
            if (!content.isArray()) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : content) {
                JsonNode text = part.get("text");
                if (text != null) {
                    sb.append(text.asText());
                }
            }
            return sb.length() == 0 ? null : sb.toString();
        }

        String getApiError() {
            return apiError;
        }

        String getCollected() {
            return collected.toString();
        }

        String getFullText() {
            if (resultText != null && !resultText.isEmpty()) {
                return resultText;
            }
            return streamedText.toString();
        }
    }
}
