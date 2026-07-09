package com.github.wechat.ilink.bot.mode.claude;

import com.github.wechat.ilink.bot.llm.ModelsConfig;
import com.github.wechat.ilink.bot.config.TaskConfig;
import com.github.wechat.ilink.bot.util.AppPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.File;
import java.util.HashMap;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 实证：真实唤起 {@code claude} 子进程 + 真实 {@code data/models-config.json}，验证 Claude Bridge 子进程链路。
 *
 * <p>触发：{@code CLAUDE_BRIDGE_LIVE=true mvn test -Dtest=ClaudeCodeAdapterLiveTest}
 * （未设该环境变量时自动跳过，CI 不跑）。
 *
 * <p>复用生产代码路径：{@link ClaudeCodeAdapter#buildArgs} + {@link ClaudeCodeAdapter#applyBridgeEnv}，
 * 即被测对象就是生产逻辑。打印下发 env（脱敏）+ claude 全量 stdout/stderr + exit code，
 * 用于定位 {@code 400 [1211] 模型不存在} 到底是哪个模型：绿=生产代码已 OK；红=输出精确指出报错的模型名。
 *
 * <p>配置来源：models-config.json，由 {@link #resolveModelsConfigPath()} 可移植定位
 * （环境变量 {@code MODELS_CONFIG_PATH} → 生产 {@code AppPaths.data()} → 向上查找 {@code data/}）。
 */
@EnabledIfEnvironmentVariable(named = "CLAUDE_BRIDGE_LIVE", matches = "true")
class ClaudeCodeAdapterLiveTest {

    private static final String PROMPT = "只回复两个字：你好";
    private static final long TIMEOUT_MS = 120_000L;
    private static final int TAIL_LEN = 4000;

    @TempDir
    File tempDir;

    public static void main(String[] args) throws Exception {
        ClaudeCodeAdapterLiveTest t = new ClaudeCodeAdapterLiveTest();
        // main 不经 JUnit，@TempDir 不会注入，手动建一个工作目录
        t.tempDir = Files.createTempDirectory("claude-bridge-live-").toFile();
        System.out.println("\n===== A) 当前生产 code-built（bridge.model）=====");
        try {
            t.subprocess_codeBuiltEnv_returnsResult();
            System.out.println(">>> A 结果：成功");
        } catch (Throwable e) {
            System.out.println(">>> A 结果：失败 " + rootMsg(e));
        }
        System.out.println("\n===== B) 提权档写文件（bypass 回归）=====");
        try {
            t.subprocess_privilegedTier_canWriteFile();
            System.out.println(">>> B 结果：成功");
        } catch (Throwable e) {
            System.out.println(">>> B 结果：失败 " + rootMsg(e));
        }
    }

    private static String rootMsg(Throwable e) {
        StringBuilder sb = new StringBuilder();
        Throwable cur = e;
        while (cur != null) {
            if (sb.length() > 0) {
                sb.append(" | cause: ");
            }
            sb.append(cur.getClass().getSimpleName()).append(": ").append(cur.getMessage());
            cur = cur.getCause();
        }
        return sb.toString();
    }

    @Test
    void subprocess_codeBuiltEnv_returnsResult() throws Exception {
        TaskConfig cfg = buildBridgeConfig();
        ClaudeCodeAdapter adapter = new ClaudeCodeAdapter(cfg);
        List<String> args = adapter.buildArgs(PROMPT, null, false, false);

        Map<String, String> env = new HashMap<String, String>();
        ClaudeCodeAdapter.applyBridgeEnv(env, cfg);
        runAndAssert("code-built", args, env);
    }

    /**
     * P0 实证：提权档（privileged=true）下 claude 真能写文件。
     *
     * <p>复刻生产提权档参数（{@code --permission-mode bypassPermissions} + {@code --dangerously-skip-permissions}），
     * 让 claude 在工作目录写一个标记文件并断言落盘。此前 LiveTest 只覆盖受限档（{@code buildArgs(..., false, false)}），
     * 提权档从未对真 claude 验证过——这正是 P0 漏网根因。marker 不存在即说明 bypass 仍未进、Write 被收走。
     */
    @Test
    void subprocess_privilegedTier_canWriteFile() throws Exception {
        TaskConfig cfg = buildBridgeConfig();
        ClaudeCodeAdapter adapter = new ClaudeCodeAdapter(cfg);
        File marker = new File(tempDir, "bypass-marker.txt");
        String prompt = "在当前工作目录创建文件 bypass-marker.txt，内容恰好为一行：BYPASS_OK。"
                + "只做这一件事，完成后回复两个字：完成。";
        List<String> args = adapter.buildArgs(prompt, null, true, false);

        // 提权档必须带 --dangerously-skip-permissions（P0 修复）：headless 下不带它，bypass 确认进不去 → Write 被收走
        assertTrue(args.contains("--dangerously-skip-permissions"),
                "提权档应下发 --dangerously-skip-permissions");

        Map<String, String> env = new HashMap<String, String>();
        ClaudeCodeAdapter.applyBridgeEnv(env, cfg);
        runAndAssert("privileged-write", args, env);

        assertTrue(marker.exists(),
                "提权档应能写文件（bypass 生效）；marker 不存在 = bypass 仍未进、Write 被收走。STDOUT/STDERR 见上");
        if (marker.exists()) {
            String content = new String(Files.readAllBytes(marker.toPath()), StandardCharsets.UTF_8).trim();
            assertTrue(content.contains("BYPASS_OK"), "marker 内容不符: " + content);
        }
    }

    /** 跑 claude 子进程，打印 CMD/ENV/EXIT/STDOUT/STDERR 并断言成功。env 合并进继承的 JVM env（含 PATH）。 */
    private void runAndAssert(String tag, List<String> args, Map<String, String> env) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(args);
        pb.directory(tempDir);
        pb.redirectErrorStream(false);
        pb.environment().putAll(env);

        System.out.println("[" + tag + " CMD]    " + String.join(" ", args));
        System.out.println("[" + tag + " ENV]    " + maskEnv(env));

        Process process = pb.start();
        StringBuilder outBuf = new StringBuilder();
        StringBuilder errBuf = new StringBuilder();
        Thread outReader = readThread(process.getInputStream(), outBuf);
        Thread errReader = readThread(process.getErrorStream(), errBuf);
        outReader.start();
        errReader.start();

        boolean finished = process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly().waitFor(2, TimeUnit.SECONDS);
        }
        outReader.join(2000);
        errReader.join(2000);

        int exit = finished ? process.exitValue() : -1;
        String stdout = outBuf.toString();
        String stderr = errBuf.toString();

        System.out.println("[" + tag + " EXIT]   " + exit + (finished ? "" : " (TIMEOUT)"));
        System.out.println("[" + tag + " STDOUT] " + tail(stdout));
        System.out.println("[" + tag + " STDERR] " + tail(stderr));

        assertTrue(finished, "[" + tag + "] claude 子进程超时 (" + TIMEOUT_MS + "ms)");
        boolean hasError = stdout.contains("\"is_error\":true")
                || stdout.contains("API Error")
                || stdout.contains("1211")
                || stderr.contains("API Error")
                || stderr.contains("1211");
        assertTrue(exit == 0 && !hasError,
                "[" + tag + "] claude 子进程失败 exit=" + exit + "。STDOUT=" + tail(stdout, 2000)
                        + " | STDERR=" + tail(stderr, 2000));
    }

    /**
     * 可移植定位 models-config.json：优先 {@code MODELS_CONFIG_PATH} 环境变量，
     * 其次生产 {@link AppPaths#data(String)} 解析，最后从当前目录向上查找 {@code data/models-config.json}。
     * 三者都找不到则抛异常——绝不回退到任何硬编码本机路径。
     */
    private static String resolveModelsConfigPath() {
        String env = System.getenv("MODELS_CONFIG_PATH");
        if (env != null && !env.isEmpty() && Files.exists(Paths.get(env))) {
            return env;
        }
        String viaAppPaths = AppPaths.data("models-config.json");
        if (Files.exists(Paths.get(viaAppPaths))) {
            return viaAppPaths;
        }
        Path cursor = Paths.get(".").toAbsolutePath().normalize();
        for (int i = 0; i < 4; i++) {
            Path candidate = cursor.resolve("data").resolve("models-config.json");
            if (Files.exists(candidate)) {
                return candidate.toString();
            }
            Path parent = cursor.getParent();
            if (parent == null) {
                break;
            }
            cursor = parent;
        }
        throw new IllegalStateException("找不到 models-config.json：请设置环境变量 MODELS_CONFIG_PATH 指向有效文件。");
    }

    /** 复刻 {@code GameApplication.injectModelConfig} 的 bridge 注入逻辑（不依赖私有方法）。 */
    private TaskConfig buildBridgeConfig() {
        ModelsConfig mc = ModelsConfig.load(resolveModelsConfigPath());
        TaskConfig cfg = new TaskConfig();

        ModelsConfig.Provider ds = mc.dashscope();
        if (isSet(ds.getBaseUrl())) {
            cfg.setDashscopeBaseUrl(ds.getBaseUrl());
        }
        cfg.setDashscopeApiKey(ds.getApiKey());
        if (isSet(ds.getUploadsUrl())) {
            cfg.setDashscopeUploadsUrl(ds.getUploadsUrl());
        }
        cfg.setClaudeBridgeModel(mc.bridgeModel());
        cfg.setClaudeBridgeSmallModel(mc.bridgeSmallModel());
        cfg.setClaudeBridgeEnabled(true);

        // Bridge 走 bridge.provider 解析的 Anthropic 端点/凭证；未声明回退 dashscope*
        ModelsConfig.Provider bridgeProvider = mc.bridgeProvider();
        if (bridgeProvider != null) {
            cfg.setClaudeBridgeBaseUrl(bridgeProvider.getBaseUrl());
            cfg.setClaudeBridgeApiKey(bridgeProvider.getApiKey());
        }
        cfg.setClaudeBridgeCwd(tempDir.getAbsolutePath());
        cfg.setClaudeHome(tempDir.getAbsolutePath());
        cfg.setTimeoutMs(TIMEOUT_MS);
        return cfg;
    }

    private static Thread readThread(final InputStream in, final StringBuilder buf) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                try {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        buf.append(line).append('\n');
                    }
                } catch (Exception ignored) {
                    // stream closed on process exit
                }
            }
        });
        t.setDaemon(true);
        return t;
    }

    /** 脱敏 env 摘要：模型角色 + baseUrl + authSet(布尔) + flags，不打印 token 值。 */
    private static String maskEnv(Map<String, String> env) {
        return "model=" + env.get("ANTHROPIC_MODEL")
                + ", small=" + env.get("ANTHROPIC_SMALL_FAST_MODEL")
                + ", haiku=" + env.get("ANTHROPIC_DEFAULT_HAIKU_MODEL")
                + ", sonnet=" + env.get("ANTHROPIC_DEFAULT_SONNET_MODEL")
                + ", opus=" + env.get("ANTHROPIC_DEFAULT_OPUS_MODEL")
                + ", reasoning=" + env.get("ANTHROPIC_REASONING_MODEL")
                + ", baseUrl=" + env.get("ANTHROPIC_BASE_URL")
                + ", authSet=" + (env.get("ANTHROPIC_AUTH_TOKEN") != null)
                + ", disableBetas=" + env.get("CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS")
                + ", toolSearch=" + env.get("ENABLE_TOOL_SEARCH");
    }

    private static boolean isSet(String s) {
        return s != null && !s.isEmpty();
    }

    private static String tail(String s) {
        return tail(s, TAIL_LEN);
    }

    /** 超长则只留尾部（错误/result 行通常在末尾）。 */
    private static String tail(String s, int max) {
        if (s == null) {
            return "<null>";
        }
        return s.length() > max ? "...[head truncated]..." + s.substring(s.length() - max) : s;
    }
}
