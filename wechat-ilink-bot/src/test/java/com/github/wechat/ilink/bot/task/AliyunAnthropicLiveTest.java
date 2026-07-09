package com.github.wechat.ilink.bot.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.wechat.ilink.bot.llm.ModelsConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 直连 DashScope 的 Anthropic 兼容端点（绕开 claude CLI），验证 models-config.json 中
 * review/bridge 模型对端点可用、且不返回 {@code model:<synthetic>} 0-token 错误。
 *
 * 触发方式：{@code set ALIYUN_ANTHROPIC_LIVE=true && mvn test -Dtest=AliyunAnthropicLiveTest}
 * 未设置该环境变量时自动跳过，CI 不会跑。
 *
 * 配置来源：{@code ../data/models-config.json}（相对模块根目录）—— 共享 DashScope provider + review/bridge 模型。
 */
@EnabledIfEnvironmentVariable(named = "ALIYUN_ANTHROPIC_LIVE", matches = "true")
class AliyunAnthropicLiveTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CONFIG_PATH = "../data/models-config.json";
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 60_000;
    private static final String USER_PROMPT = "只回复两个字：你好";

    @Test
    void chat_eachConfiguredModel_returnsContentOrSurfacesError() throws Exception {
        ModelsConfig config = ModelsConfig.load(CONFIG_PATH);
        ModelsConfig.Provider ds = config.dashscope();
        assertTrue(!ds.getBaseUrl().isEmpty(),
                "models-config.json 未配置 dashscope.baseUrl，无法测试");
        assertTrue(!ds.getApiKey().isEmpty(),
                "models-config.json 未配置 dashscope.apiKey，无法测试");

        Map<String, String> rolesToModel = new LinkedHashMap<String, String>();
        rolesToModel.put("review", config.reviewModel());
        rolesToModel.put("bridge", config.bridgeModel());

        List<ModelResult> results = new ArrayList<ModelResult>();
        for (Map.Entry<String, String> entry : rolesToModel.entrySet()) {
            String role = entry.getKey();
            String model = entry.getValue();
            if (model == null || model.isEmpty()) {
                System.out.println("[SKIP]    role=" + role + " model=<empty>");
                continue;
            }
            ModelResult result = callAliyun(ds, model);
            result.role = role;
            results.add(result);
            System.out.println("[RESULT]  role=" + role
                    + " model=" + model
                    + " status=" + result.status
                    + " content=" + truncate(result.content, 80)
                    + " body=" + truncate(result.rawBody, 500));
        }

        int ok = 0;
        StringBuilder failures = new StringBuilder();
        for (ModelResult r : results) {
            if (r.status == 200 && r.content != null && !r.content.isEmpty()) {
                ok++;
            } else {
                failures.append("\n  role=").append(r.role)
                        .append(" model=").append(r.model)
                        .append(" status=").append(r.status)
                        .append(" body=").append(truncate(r.rawBody, 400));
            }
        }

        assertTrue(ok > 0,
                "Aliyun Anthropic 端点没有一个模型调用成功 —— "
                        + "可能是 baseUrl/authToken 错误，或网络不通。失败明细：" + failures);
        if (failures.length() > 0) {
            System.out.println("[WARN] 部分模型失败，建议核对模型名：" + failures);
        }
    }

    private ModelResult callAliyun(ModelsConfig.Provider ds, String model) throws Exception {
        String endpoint = trimTrailingSlash(ds.getBaseUrl()) + "/v1/messages";
        String requestBody = buildRequestBody(model);

        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("x-api-key", ds.getApiKey());
        conn.setRequestProperty("anthropic-version", "2023-06-01");
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setDoOutput(true);

        byte[] body = requestBody.getBytes(StandardCharsets.UTF_8);
        conn.setRequestProperty("Content-Length", String.valueOf(body.length));

        OutputStream os = conn.getOutputStream();
        os.write(body);
        os.flush();
        os.close();

        int status = conn.getResponseCode();
        InputStream stream = (status >= 200 && status < 300)
                ? conn.getInputStream()
                : conn.getErrorStream();
        String rawBody = readAll(stream);

        ModelResult result = new ModelResult();
        result.model = model;
        result.status = status;
        result.rawBody = rawBody;
        result.content = (status >= 200 && status < 300) ? extractContent(rawBody) : null;
        return result;
    }

    private String buildRequestBody(String model) throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", model);
        root.put("max_tokens", 64);
        ObjectNode thinking = MAPPER.createObjectNode();
        thinking.put("type", "disabled");
        root.set("thinking", thinking);

        ArrayNode messages = MAPPER.createArrayNode();
        ObjectNode userMsg = MAPPER.createObjectNode();
        userMsg.put("role", "user");
        userMsg.put("content", USER_PROMPT);
        messages.add(userMsg);
        root.set("messages", messages);

        return MAPPER.writeValueAsString(root);
    }

    private static String extractContent(String rawBody) {
        if (rawBody == null || rawBody.isEmpty()) return null;
        try {
            JsonNode root = MAPPER.readTree(rawBody);
            JsonNode content = root.get("content");
            if (content == null || !content.isArray() || content.size() == 0) {
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
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String readAll(InputStream in) {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        } catch (Exception ignored) {
            // best-effort read for diagnostics
        } finally {
            try { reader.close(); } catch (Exception ignored) {}
        }
        return sb.toString();
    }

    private static String trimTrailingSlash(String s) {
        if (s == null) return "";
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "<null>";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    private static final class ModelResult {
        String role;
        String model;
        int status;
        String rawBody;
        String content;
    }
}
