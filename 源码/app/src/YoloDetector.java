
import java.io.*;
import java.util.*;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.BasicStroke;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

/**
 * YOLO 目标检测器
 *
 * 通过调用 Python 脚本（yolo_infer.py）运行 YOLO 模型，
 * 识别图片中的缺陷目标，并在图片上画出检测框。
 *
 * 一行调用示例：
 * <pre>
 *   // 只获取结果图片路径
 *   String resultPath = YoloDetector.detect("图片.jpg");
 *
 *   // 同时获取检测详情
 *   List&lt;YoloDetector.Detection&gt; dets = new ArrayList&lt;&gt;();
 *   String resultPath = YoloDetector.detect("图片.jpg", dets);
 *   for (Detection d : dets) {
 *       System.out.println(d.className + " 置信度:" + d.confidence);
 *   }
 * </pre>
 *
 * 前置条件：系统需安装 Python 并配置 ultralytics 库，放置好 .pt 模型文件。
 *
 * @author 长沙理工大学
 */
public class YoloDetector {

    /** 项目根目录 */
    private static final String BASE_DIR = System.getProperty("user.dir");

    /** YOLO 推理超时时间（秒） */
    private static final long TIMEOUT_SECONDS = 300;

    /** 默认检测器实例（单例，避免重复加载模型） */
    private static YoloDetector defaultInstance;

    private final String modelPath;   // 模型文件路径
    private final String outputDir;   // 结果图片输出目录

    // ==================== 构造方法 ====================

    /** 使用默认模型（src/best.pt）和默认输出目录 */
    public YoloDetector() {
        this(BASE_DIR + "/best.pt", BASE_DIR + "/aioutput/images");
    }

    /**
     * 自定义模型路径和输出目录
     *
     * @param modelPath  YOLO .pt 模型文件路径
     * @param outputDir  检测结果图片的输出目录
     */
    public YoloDetector(String modelPath, String outputDir) {
        this.modelPath = modelPath;
        this.outputDir = outputDir;
        new File(outputDir).mkdirs();
    }

    // ==================== 静态便捷方法（推荐使用） ====================

    /**
     * 检测图片中的目标，返回带框的结果图片路径
     *
     * @param imagePath  输入图片路径
     * @return 带检测框的结果图片路径
     */
    public static String detect(String imagePath) throws Exception {
        if (defaultInstance == null) {
            defaultInstance = new YoloDetector();
        }
        return defaultInstance.doDetect(imagePath);
    }

    /**
     * 检测图片中的目标，同时获取检测详情列表
     *
     * @param imagePath      输入图片路径
     * @param outDetections  输出参数：检测结果详情会添加到这个列表中
     * @return 带检测框的结果图片路径
     */
    public static String detect(String imagePath, List<Detection> outDetections) throws Exception {
        if (defaultInstance == null) {
            defaultInstance = new YoloDetector();
        }
        return defaultInstance.doDetect(imagePath, outDetections);
    }

    /**
     * 使用指定模型检测图片
     *
     * @param imagePath  输入图片路径
     * @param modelPath  模型文件路径
     * @return 带检测框的结果图片路径
     */
    public static String detect(String imagePath, String modelPath) throws Exception {
        return new YoloDetector(modelPath, BASE_DIR + "/aioutput/images").doDetect(imagePath);
    }

    // ==================== 实例方法 ====================

    /** 实例版：检测图片 */
    public String detectImage(String imagePath) throws Exception {
        return doDetect(imagePath);
    }

    /** 实例版：检测图片并获取详情 */
    public String detectImage(String imagePath, List<Detection> outDetections) throws Exception {
        return doDetect(imagePath, outDetections);
    }

    // ==================== 核心检测流程 ====================

    /**
     * 执行检测的完整流程：
     * 1. 调用 Python 脚本进行 YOLO 推理
     * 2. 解析 JSON 结果
     * 3. 在原图上画框
     * 4. 返回结果图片路径
     */
    private String doDetect(String imagePath) throws Exception {
        return doDetect(imagePath, null);
    }

    private String doDetect(String imagePath, List<Detection> outDetections) throws Exception {
        // 步骤1：调用 Python 获取检测结果 JSON + 标注图片
        File jsonFile = File.createTempFile("yolo_", ".json");
        String annotatedPath = runPythonInference(imagePath, jsonFile.getAbsolutePath());

        // 步骤2：解析 JSON
        String json = readText(jsonFile.getAbsolutePath());
        jsonFile.delete();

        List<Detection> detections = parseJsonArray(json);
        if (outDetections != null) {
            outDetections.addAll(detections);
        }

        // 步骤3：返回标注图片路径（Python 已画框）
        if (annotatedPath != null && new File(annotatedPath).exists()) {
            return annotatedPath;
        }
        // 回退：Python 未生成标注图时 Java 自己画
        return drawBoxes(imagePath, detections);
    }

    /**
     * 启动 Python 进程运行 yolo_infer.py
     *
     * 注意：输出读取放在独立线程中，避免因 Python 进程长时间无输出
     * （如模型加载期间）导致 reader.readLine() 阻塞，使 waitFor 超时失效。
     */
    /** 返回 Python 生成的标注图片路径（如有） */
    private String runPythonInference(String imagePath, String jsonOutputPath) throws Exception {
        String scriptPath = BASE_DIR + "/yolo_infer.py";

        ProcessBuilder pb = new ProcessBuilder(
            "python", scriptPath,
            "--model", modelPath,
            "--image", imagePath,
            "--output", jsonOutputPath,
            "--annotate-dir", outputDir
        );
        pb.redirectErrorStream(true);
        Process process = pb.start();

        // 独立线程读取输出
        StringBuilder output = new StringBuilder();
        final String[] annotatedPath = {null};
        Thread readerThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    if (line.startsWith("ANNOTATED:")) {
                        annotatedPath[0] = line.substring("ANNOTATED:".length()).trim();
                    }
                }
            } catch (IOException ignored) {
            }
        }, "yolo-output-reader");
        readerThread.start();

        // 等待进程结束（超时保护在主线程生效）
        boolean finished = process.waitFor(TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            readerThread.interrupt();
            throw new RuntimeException(
                "YOLO 推理超时（" + TIMEOUT_SECONDS + "秒），请检查 Python 环境和 ultralytics 库\n" + output
            );
        }
        // 等待读取线程收尾
        readerThread.join(5000);
        if (process.exitValue() != 0) {
            throw new RuntimeException("YOLO 推理失败\n" + output);
        }
        return annotatedPath[0];
    }

    /**
     * 在图片上画出检测框和标签
     */
    private String drawBoxes(String imagePath, List<Detection> detections) throws IOException {
        BufferedImage img = ImageIO.read(new File(imagePath));
        Graphics2D g = img.createGraphics();
        g.setStroke(new BasicStroke(2));

        for (Detection d : detections) {
            int x = (int) d.x1;
            int y = (int) d.y1;
            int w = (int) (d.x2 - d.x1);
            int h = (int) (d.y2 - d.y1);

            // 红色边框
            g.setColor(Color.RED);
            g.drawRect(x, y, w, h);

            // 标签背景和文字
            String label = d.className + " " + String.format("%.2f", d.confidence);
            g.setFont(new Font("Arial", Font.PLAIN, 12));
            FontMetrics fm = g.getFontMetrics();

            int labelY = y - 2;
            if (labelY < fm.getAscent()) {
                labelY = y + fm.getAscent();
            }

            g.setColor(new Color(255, 0, 0, 180));
            g.fillRect(x, labelY - fm.getAscent(), fm.stringWidth(label) + 4, fm.getHeight());
            g.setColor(Color.WHITE);
            g.drawString(label, x + 2, labelY);
        }
        g.dispose();

        // 保存结果图片
        String fileName = "yolo_" + System.currentTimeMillis() + ".png";
        File outFile = new File(outputDir, fileName);
        ImageIO.write(img, "png", outFile);
        System.out.println("检测到 " + detections.size() + " 个目标 → " + outFile.getAbsolutePath());
        return outFile.getAbsolutePath();
    }

    // ==================== JSON 解析工具 ====================

    /** 读取文件全部文本 */
    private String readText(String path) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    /** 从 JSON 数组中解析检测对象列表 */
    private List<Detection> parseJsonArray(String json) {
        List<Detection> list = new ArrayList<>();
        int depth = 0;
        int start = -1;

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    list.add(parseOneDetection(json.substring(start, i + 1)));
                    start = -1;
                }
            }
        }
        return list;
    }

    /** 从单个 JSON 对象中提取 Detection */
    private Detection parseOneDetection(String obj) {
        return new Detection(
            extractDouble(obj, "x1"),
            extractDouble(obj, "y1"),
            extractDouble(obj, "x2"),
            extractDouble(obj, "y2"),
            extractDouble(obj, "confidence"),
            extractString(obj, "class")
        );
    }

    /** 从 JSON 片段中提取指定 key 的 double 值 */
    private double extractDouble(String json, String key) {
        String search = "\"" + key + "\":";
        int i = json.indexOf(search);
        if (i < 0) return 0;
        i += search.length();

        int end = i;
        while (end < json.length() &&
               (Character.isDigit(json.charAt(end)) || json.charAt(end) == '.' ||
                json.charAt(end) == '-' || json.charAt(end) == 'e' || json.charAt(end) == 'E')) {
            end++;
        }
        return Double.parseDouble(json.substring(i, end));
    }

    /** 从 JSON 片段中提取指定 key 的字符串值 */
    private String extractString(String json, String key) {
        String search = "\"" + key + "\":\"";
        int i = json.indexOf(search);
        if (i < 0) return "";
        i += search.length();

        int end = json.indexOf("\"", i);
        return end < 0 ? json.substring(i) : json.substring(i, end);
    }

    // ==================== 检测结果数据结构 ====================

    /**
     * 一次检测结果
     */
    public static class Detection {
        public final double x1, y1, x2, y2;   // 边界框坐标
        public final double confidence;         // 置信度 (0~1)
        public final String className;          // 类别名称

        public Detection(double x1, double y1, double x2, double y2,
                         double confidence, String className) {
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
            this.confidence = confidence;
            this.className = className;
        }

        @Override
        public String toString() {
            return className + "(" + String.format("%.2f", confidence) + ") [" +
                   (int) x1 + "," + (int) y1 + " " + (int) x2 + "," + (int) y2 + "]";
        }
    }

    // ==================== 命令行测试入口 ====================

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("用法: java YoloDetector <图片路径> [模型路径]");
            return;
        }
        String result = args.length >= 2
            ? YoloDetector.detect(args[0], args[1])
            : YoloDetector.detect(args[0]);
        System.out.println("输出: " + result);
    }
}
