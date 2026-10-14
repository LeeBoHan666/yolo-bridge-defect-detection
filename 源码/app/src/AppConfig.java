import java.io.*;
import java.util.Properties;

/**
 * 应用程序全局配置（含持久化读写）
 *
 * 存储 Ollama 模型连接信息、图片路径和输出路径等全局参数。
 * 所有字段为静态字段，方便各个界面组件统一读写。
 * 通过 save() / load() 方法将配置持久化到 aioutput/config.properties。
 *
 * @author 长沙理工大学
 */
public class AppConfig {

    // ==================== 配置文件路径 ====================

    private static final File CONFIG_FILE = new File(
        System.getProperty("user.dir") + "/src/aioutput/config.properties"
    );

    // ==================== Ollama 服务配置 ====================

    /** Ollama API 默认地址 */
    public static final String DEFAULT_URL = "http://localhost:11434/api/generate";

    /** 默认使用的模型名称 */
    public static final String DEFAULT_MODEL = "gemma4:e4b";

    /** 当前使用的 API 地址（可在设置页面修改） */
    public static String url = DEFAULT_URL;

    /** 当前使用的模型（可在设置页面修改） */
    public static String model = DEFAULT_MODEL;

    /** API 密钥（如不需要可留空） */
    public static String api = "";

    // ==================== 图片与输出路径 ====================

    /** 用户选择的待检测图片路径 */
    public static String imgPath;

    /** 输出文件的根目录 */
    public static String outputPath = System.getProperty("user.dir") + "/src/aioutput/";

    /** 最近一次输出的文件名 */
    public static String fileName;

    /** 最近一次输出的完整路径 */
    public static String fullOutPath;

    // ==================== 最近一次问答 ====================

    /** 用户最近一次提问 */
    public static String question = "图片里面有什么";

    /** AI 最近一次回答 */
    public static String answer;

    // ==================== 配置持久化 ====================

    /**
     * 保存当前配置到文件
     */
    public static void save() {
        CONFIG_FILE.getParentFile().mkdirs();
        Properties props = new Properties();
        props.setProperty("url", url);
        props.setProperty("model", model);
        props.setProperty("api", api == null ? "" : api);
        props.setProperty("outputPath", outputPath);

        try (FileOutputStream out = new FileOutputStream(CONFIG_FILE)) {
            props.store(out, "土木智能运维系统配置");
        } catch (IOException e) {
            System.err.println("配置保存失败: " + e.getMessage());
        }
    }

    /**
     * 从文件加载配置，覆盖当前默认值
     */
    public static void load() {
        if (!CONFIG_FILE.exists()) return;

        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream(CONFIG_FILE)) {
            props.load(in);

            if (props.containsKey("url"))
                url = props.getProperty("url");
            if (props.containsKey("model"))
                model = props.getProperty("model");
            if (props.containsKey("api"))
                api = props.getProperty("api");
            if (props.containsKey("outputPath"))
                outputPath = props.getProperty("outputPath");
        } catch (IOException e) {
            System.err.println("配置加载失败: " + e.getMessage());
        }
    }
}
