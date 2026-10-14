import java.io.*;
import java.nio.file.*;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * AI 对话客户端 — Java 前端 → Python 后端桥接
 *
 * 所有 LLM 通信由 Python llm_backend.py 完成。
 * Java 只负责：传参、读取结果、回调前端。
 *
 * @author 长沙理工大学
 */
public class OllamaChat {

    /** Python 脚本路径（相对于 user.dir） */
    private static final String BACKEND_SCRIPT = "llm_backend.py";

    /** 超时时间（秒） */
    private static final long TIMEOUT_SEC = 300;

    // ==================== 纯文本对话 ====================

    /**
     * 非流式文本对话
     *
     * @param url    Ollama API 地址
     * @param model  模型名称
     * @param prompt 提示词
     * @return AI 回答文本
     */
    public static String send(String url, String model, String prompt) throws Exception {
        File outputFile = File.createTempFile("llm_chat_", ".json");

        ProcessBuilder pb = new ProcessBuilder(
            "python", BACKEND_SCRIPT,
            "--mode", "chat",
            "--url", url,
            "--model", model,
            "--prompt", prompt,
            "--output", outputFile.getAbsolutePath()
        );
        pb.redirectErrorStream(true);

        Process proc = pb.start();
        String log = readAll(proc.getInputStream());
        boolean finished = proc.waitFor(TIMEOUT_SEC, TimeUnit.SECONDS);

        if (!finished) {
            proc.destroyForcibly();
            throw new RuntimeException("LLM 超时（" + TIMEOUT_SEC + "秒）");
        }
        if (proc.exitValue() != 0) {
            throw new RuntimeException("LLM 调用失败\n" + log);
        }

        String json = Files.readString(outputFile.toPath());
        outputFile.delete();

        return parseResponse(json);
    }

    // ==================== 流式对话 ====================

    /**
     * 流式文本对话，逐 token 回调（打字机效果）
     *
     * @param url        Ollama API 地址
     * @param model      模型名称
     * @param prompt     提示词
     * @param onToken    每收到一个 token 时回调
     * @param cancelFlag 取消标志
     * @return 完整的 AI 回答文本
     */
    public static String sendStream(String url, String model, String prompt,
                                     Consumer<String> onToken,
                                     AtomicBoolean cancelFlag) throws Exception {
        File outputFile = File.createTempFile("llm_stream_", ".json");

        ProcessBuilder pb = new ProcessBuilder(
            "python", BACKEND_SCRIPT,
            "--mode", "stream",
            "--url", url,
            "--model", model,
            "--prompt", prompt,
            "--output", outputFile.getAbsolutePath()
        );
        pb.redirectErrorStream(true);

        Process proc = pb.start();

        // 独立线程读取 stdout 中的 token
        StringBuilder fullResponse = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if ("__STREAM_DONE__".equals(line)) break;
                    fullResponse.append(line);
                    onToken.accept(line);
                }
            } catch (IOException ignored) {}
        }, "llm-stream-reader");
        reader.start();

        // 等待进程结束或取消
        boolean finished = false;
        while (!finished && reader.isAlive()) {
            if (cancelFlag != null && cancelFlag.get()) {
                proc.destroyForcibly();
                reader.interrupt();
                outputFile.delete();
                return fullResponse.toString();
            }
            finished = proc.waitFor(500, TimeUnit.MILLISECONDS);
        }
        reader.join(5000);

        if (!finished) {
            proc.destroyForcibly();
        }

        // 读 JSON 结果
        String response = fullResponse.toString();
        try {
            if (outputFile.exists()) {
                String json = Files.readString(outputFile.toPath());
                outputFile.delete();
                response = parseResponse(json);
            }
        } catch (Exception ignored) {}
        return response;
    }

    // ==================== 图片分析 ====================

    /**
     * 图片 + 文字多模态分析
     *
     * @param url         Ollama API 地址
     * @param model       模型名称（需支持视觉）
     * @param prompt      文字提示词
     * @param imageBase64 图片 Base64 编码
     * @return AI 分析结果
     */
    public static String sendImage(String url, String model, String prompt,
                                    String imageBase64) throws Exception {
        // Base64 太长无法通过命令行传递，写入临时文件
        File b64File = File.createTempFile("llm_img_b64_", ".txt");
        Files.writeString(b64File.toPath(), imageBase64);

        File outputFile = File.createTempFile("llm_image_", ".json");

        ProcessBuilder pb = new ProcessBuilder(
            "python", BACKEND_SCRIPT,
            "--mode", "image",
            "--url", url,
            "--model", model,
            "--prompt", prompt,
            "--image-base64-file", b64File.getAbsolutePath(),
            "--output", outputFile.getAbsolutePath()
        );
        pb.redirectErrorStream(true);

        Process proc = pb.start();
        String log = readAll(proc.getInputStream());
        boolean finished = proc.waitFor(TIMEOUT_SEC, TimeUnit.SECONDS);

        b64File.delete();

        if (!finished) {
            proc.destroyForcibly();
            throw new RuntimeException("LLM 图片分析超时");
        }
        if (proc.exitValue() != 0) {
            throw new RuntimeException("LLM 图片分析失败\n" + log);
        }

        String json = Files.readString(outputFile.toPath());
        outputFile.delete();

        return parseResponse(json);
    }

    // ==================== 工具方法 ====================

    private static String readAll(InputStream is) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append("\n");
        }
        return sb.toString();
    }

    /** 解析 Python 后端 JSON: {"success": true, "response": "..."} */
    private static String parseResponse(String json) throws Exception {
        // 简单解析（不依赖第三方库）
        String successKey = "\"success\"";
        int si = json.indexOf(successKey);
        if (si < 0) throw new RuntimeException("LLM 返回格式错误: " + json);

        int colon = json.indexOf(":", si);
        String after = json.substring(colon + 1).trim();
        boolean success = after.startsWith("true");

        if (!success) {
            String err = extractString(json, "error");
            throw new RuntimeException("LLM 调用失败: " + err);
        }

        return extractString(json, "response");
    }

    private static String extractString(String json, String key) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return "";

        int colon = json.indexOf(":", idx);
        if (colon < 0) return "";

        int start = json.indexOf("\"", colon + 1);
        if (start < 0) return "";
        start++;

        StringBuilder sb = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char n = json.charAt(i + 1);
                switch (n) {
                    case 'n': sb.append('\n'); i++; break;
                    case 't': sb.append('\t'); i++; break;
                    case 'r': sb.append('\r'); i++; break;
                    case '"': sb.append('"'); i++; break;
                    case '\\': sb.append('\\'); i++; break;
                    default: sb.append(c); break;
                }
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
