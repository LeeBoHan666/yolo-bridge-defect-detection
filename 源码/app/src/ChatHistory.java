
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * 对话历史记录器
 *
 * 将用户与 AI 的对话以 Markdown 格式自动保存到 aioutput/chat/ 目录。
 * 支持新建会话、追加消息、加载历史、列出所有会话文件。
 *
 * @author 长沙理工大学
 */
public class ChatHistory {

    /** 对话记录存放目录 */
    private static final String CHAT_DIR =
        System.getProperty("user.dir") + "/src/aioutput/chat";

    /** 当前会话的保存文件 */
    private File currentFile;

    /**
     * 构造时确保输出目录存在
     */
    public ChatHistory() {
        new File(CHAT_DIR).mkdirs();
    }

    /**
     * 新建一个会话文件（以当前时间戳命名）
     */
    public void newSession() {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String fileName = "chat_" + timestamp + ".md";
        currentFile = new File(CHAT_DIR, fileName);
    }

    /**
     * 向当前会话追加一条消息
     *
     * @param role  角色："user" 或 "ai"
     * @param text  消息内容
     */
    public void append(String role, String text) {
        if (currentFile == null) {
            newSession();
        }
        String tag = role.equals("user") ? "**你**" : "**AI**";
        String time = new SimpleDateFormat("HH:mm:ss").format(new Date());

        try (FileWriter writer = new FileWriter(currentFile, true)) {
            writer.write(tag + " (" + time + "):\n\n");
            writer.write(text + "\n\n");
            writer.write("---\n\n");
        } catch (IOException e) {
            System.err.println("对话记录保存失败: " + e.getMessage());
        }
    }

    /**
     * 加载指定会话文件的全部内容
     *
     * @param file  会话文件
     * @return 文件内容字符串
     */
    public String load(File file) throws IOException {
        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
        }
        return content.toString();
    }

    /**
     * 列出所有历史会话文件（按修改时间倒序排列）
     *
     * @return 会话文件列表
     */
    public List<File> listSessions() {
        File dir = new File(CHAT_DIR);
        File[] files = dir.listFiles((d, name) -> name.endsWith(".md"));
        if (files == null) {
            return Collections.emptyList();
        }
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return Arrays.asList(files);
    }

    /** 获取当前会话文件 */
    public File getCurrentFile() {
        return currentFile;
    }
}
