import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Swing 版 AI 调试工具主界面
 *
 * 界面：顶部菜单栏 / 中部图片区（拖入原图 + 检测结果）/ 底部操作面板
 */
public class Swing extends JFrame {

    /** 状态日志 */
    public static JTextArea stateJTA;
    /** 异常日志 */
    public static JTextArea exceptionJTA;
    /** 检测结果面板 */
    public static JPanel outImg;
    /** 检测结果图片 */
    static JLabel outImgLabel;

    public Swing() {
        initFrame();
    }

    private void initFrame() {
        setTitle("AI 调试工具");
        setSize(1100, 700);
        setLocation(400, 150);
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);

        // 窗口图标
        ImageIcon turtleIcon = new ImageIcon(getClass().getResource("/乌龟.png"));
        setIconImage(turtleIcon.getImage());

        // 菜单栏
        setJMenuBar(createMenuBar());

        // 主布局
        setLayout(new BorderLayout(8, 8));

        // 图片区
        JPanel imageArea = new JPanel(new GridLayout(1, 2, 8, 0));
        imageArea.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));

        ImageDropPanel img1 = new ImageDropPanel();
        img1.setBorder(BorderFactory.createTitledBorder("拖入图片（原图）"));

        outImg = new JPanel(new BorderLayout());
        outImg.setBorder(BorderFactory.createTitledBorder("检测结果"));
        outImgLabel = new JLabel("等待检测...", SwingConstants.CENTER);
        outImg.add(outImgLabel, BorderLayout.CENTER);

        imageArea.add(img1);
        imageArea.add(outImg);
        add(imageArea, BorderLayout.CENTER);

        // 操作面板
        OperatePanel op = new OperatePanel();
        op.setBorder(BorderFactory.createTitledBorder("调试控制台"));
        add(op, BorderLayout.SOUTH);

        // 关闭确认
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (JOptionPane.showConfirmDialog(null,
                        "确定关闭调试工具吗") == JOptionPane.OK_OPTION)
                    dispose();
            }
        });

        setVisible(true);
        stateJTA.append("[系统] 调试工具启动完成\n");
        stateJTA.append("[模型] " + AppConfig.model + "\n");
        stateJTA.append("[URL]  " + AppConfig.url + "\n");
    }

    // ====== 菜单栏 ======

    private JMenuBar createMenuBar() {
        JMenuBar mb = new JMenuBar();

        // 模型菜单
        JMenu modelMenu = new JMenu("模型");
        modelMenu.add(createCurrentModelItem());
        modelMenu.add(createModelChoiceItem());
        mb.add(modelMenu);

        // 调试菜单
        JMenu debugMenu = new JMenu("调试");
        debugMenu.add(createYoloInfoItem());
        debugMenu.add(createDbTestItem());
        debugMenu.add(createStateItem());
        debugMenu.add(createExceptionItem());
        mb.add(debugMenu);

        // 关于菜单
        JMenu aboutMenu = new JMenu("关于");
        aboutMenu.add(createReadmeItem());
        aboutMenu.add(createAboutItem());
        mb.add(aboutMenu);

        return mb;
    }

    private JMenuItem createCurrentModelItem() {
        JMenuItem item = new JMenuItem("当前模型");
        item.addActionListener(e -> {
            JFrame frame = new JFrame("当前模型");
            frame.setLayout(new GridLayout(7, 1, 5, 5));
            frame.setLocationRelativeTo(null);
            frame.setSize(500, 240);
            frame.add(new JLabel("URL链接：", SwingConstants.LEFT));
            frame.add(new JLabel(AppConfig.url, SwingConstants.LEFT));
            frame.add(new JLabel("model：", SwingConstants.LEFT));
            frame.add(new JLabel(AppConfig.model, SwingConstants.LEFT));
            frame.add(new JLabel("API密钥：", SwingConstants.LEFT));
            frame.add(new JLabel(AppConfig.api, SwingConstants.LEFT));
            JPanel bp = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            JButton ok = new JButton("确定");
            ok.addActionListener(ev -> frame.dispose());
            bp.add(ok);
            frame.add(bp);
            frame.setVisible(true);
        });
        return item;
    }

    private JMenuItem createModelChoiceItem() {
        JMenuItem item = new JMenuItem("模型选择");
        item.addActionListener(e -> {
            JFrame frame = new JFrame("模型选择");
            frame.setLocationRelativeTo(null);
            frame.setSize(550, 280);
            frame.setLayout(new GridLayout(7, 1));

            JPanel urlP = new JPanel(new FlowLayout(FlowLayout.LEFT));
            urlP.add(new JLabel("URL链接:", SwingConstants.LEFT));
            JTextField urlF = new JTextField(AppConfig.url, 40);
            urlP.add(urlF);

            JPanel mdlP = new JPanel(new FlowLayout(FlowLayout.LEFT));
            mdlP.add(new JLabel("model:", SwingConstants.LEFT));
            JTextField mdlF = new JTextField(AppConfig.model, 40);
            mdlP.add(mdlF);

            JPanel apiP = new JPanel(new FlowLayout(FlowLayout.LEFT));
            apiP.add(new JLabel("API密钥:", SwingConstants.LEFT));
            JTextField apiF = new JTextField(AppConfig.api, 40);
            apiP.add(apiF);

            JPanel btnP = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            JButton defBtn = new JButton("Ollama默认");
            defBtn.addActionListener(ev -> {
                urlF.setText(AppConfig.DEFAULT_URL);
                mdlF.setText(AppConfig.DEFAULT_MODEL);
                apiF.setText("");
            });
            JButton cancelBtn = new JButton("取消");
            cancelBtn.addActionListener(ev -> frame.dispose());
            JButton okBtn = new JButton("确定");
            okBtn.addActionListener(ev -> {
                AppConfig.url = urlF.getText();
                AppConfig.model = mdlF.getText();
                AppConfig.api = apiF.getText();
                stateJTA.append("[模型] 已切换 → " + AppConfig.model + "\n");
                frame.dispose();
            });
            btnP.add(defBtn); btnP.add(cancelBtn); btnP.add(okBtn);

            frame.add(urlP); frame.add(new JPanel());
            frame.add(mdlP); frame.add(new JPanel());
            frame.add(apiP); frame.add(new JPanel());
            frame.add(btnP);
            frame.setVisible(true);
        });
        return item;
    }

    private JMenuItem createYoloInfoItem() {
        JMenuItem item = new JMenuItem("YOLO 模型信息");
        item.addActionListener(e -> {
            try {
                String dir = System.getProperty("user.dir") + "/src/";
                File mf = new File(dir + "best.pt");
                StringBuilder info = new StringBuilder();
                info.append("模型路径: ").append(mf.getAbsolutePath()).append("\n");
                if (mf.exists()) info.append("文件大小: ")
                    .append(String.format("%.1f MB", mf.length() / 1024.0 / 1024.0)).append("\n");
                info.append("文件存在: ").append(mf.exists() ? "是" : "否").append("\n\n可用模型文件:\n");
                File[] pts = new File(dir).listFiles((d, n) -> n.endsWith(".pt"));
                if (pts != null) for (File f : pts)
                    info.append("  · ").append(f.getName())
                        .append(" (").append(String.format("%.1f MB", f.length() / 1024.0 / 1024.0)).append(")\n");

                JTextArea ta = new JTextArea(info.toString());
                ta.setEditable(false);
                JScrollPane sp = new JScrollPane(ta);
                sp.setPreferredSize(new Dimension(450, 250));
                JOptionPane.showMessageDialog(this, sp, "YOLO 模型信息", JOptionPane.INFORMATION_MESSAGE);
                stateJTA.append("[模型信息]\n" + info);
            } catch (Exception ex) {
                exceptionJTA.append("[模型信息] 获取失败: " + ex.getMessage() + "\n");
            }
        });
        return item;
    }

    private JMenuItem createDbTestItem() {
        JMenuItem item = new JMenuItem("数据库连接测试");
        item.addActionListener(e -> {
            stateJTA.append("[数据库] 开始连接测试...\n");
            new Thread(() -> {
                try {
                    java.sql.Connection con = java.sql.DriverManager.getConnection(
                        "jdbc:h2:file:./data/bridge;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
                        "sa", "");
                    java.sql.Statement stmt = con.createStatement();
                    java.sql.ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM defects");
                    rs.next();
                    int count = rs.getInt(1);
                    con.close();
                    SwingUtilities.invokeLater(() ->
                        stateJTA.append("[数据库] ✅ H2 连接成功！defects 表共 " + count + " 条记录\n"));
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        stateJTA.append("[数据库] ❌ 连接失败\n");
                        exceptionJTA.append("[数据库] " + ex.getMessage() + "\n");
                    });
                }
            }).start();
        });
        return item;
    }

    private JMenuItem createStateItem() {
        JFrame sf = new JFrame("状态日志");
        sf.setSize(600, 300);
        sf.setLocationRelativeTo(null);
        stateJTA = new JTextArea(15, 30);
        stateJTA.setLineWrap(true);
        stateJTA.setEditable(false);
        sf.add(new JScrollPane(stateJTA));

        JMenuItem item = new JMenuItem("状态面板");
        item.addActionListener(e -> sf.setVisible(true));
        return item;
    }

    private JMenuItem createExceptionItem() {
        JFrame ef = new JFrame("异常日志");
        ef.setSize(600, 300);
        ef.setLocationRelativeTo(null);
        exceptionJTA = new JTextArea(15, 30);
        exceptionJTA.setLineWrap(true);
        exceptionJTA.setEditable(false);
        ef.add(new JScrollPane(exceptionJTA));

        JMenuItem item = new JMenuItem("异常面板");
        item.addActionListener(e -> ef.setVisible(true));
        return item;
    }

    private JMenuItem createReadmeItem() {
        JMenuItem item = new JMenuItem("使用说明");
        item.addActionListener(e -> {
            try {
                File readme = new File("src/README.md");
                if (readme.exists()) {
                    Desktop.getDesktop().open(readme);
                    stateJTA.append("[文件] 已打开 README.md\n");
                }
            } catch (IOException ex) {
                stateJTA.append("[错误] 打开 README.md 失败\n");
                exceptionJTA.append("IOException:" + ex.getMessage() + "\n");
            }
        });
        return item;
    }

    private JMenuItem createAboutItem() {
        JMenuItem item = new JMenuItem("技术支持");
        item.addActionListener(e -> {
            JFrame f = new JFrame("关于");
            f.setLocationRelativeTo(null);
            f.setSize(500, 240);
            f.add(new JLabel("王八大王乌龟仙人@长沙理工大学 制作", SwingConstants.CENTER), BorderLayout.CENTER);
            f.setVisible(true);
        });
        return item;
    }

    // ====== 工具方法 ======

    /** 在结果面板中显示图片 */
    static void showResultImage(String imagePath) {
        if (imagePath == null || imagePath.isEmpty()) return;
        try {
            ImageIcon icon = new ImageIcon(imagePath);
            int w = outImg.getWidth() - 10;
            int h = outImg.getHeight() - 10;
            Image scaled = icon.getImage().getScaledInstance(w, h, Image.SCALE_SMOOTH);
            outImgLabel.setIcon(new ImageIcon(scaled));
            outImgLabel.setText("");
        } catch (Exception e) {
            outImgLabel.setIcon(null);
            outImgLabel.setText("图片加载失败");
        }
    }

    /** 启动 Swing 界面 */
    public static void main(String[] args) {
        new Swing();
    }
}

// ==================== 图片拖放面板 ====================

/** 支持从文件管理器拖入图片，自动缩放显示 */
class ImageDropPanel extends JPanel {

    private JLabel imageLabel;
    private ImageIcon currentIcon;

    public ImageDropPanel() {
        setLayout(new BorderLayout());
        imageLabel = new JLabel("拖图片到这里\n(或从菜单加载)", SwingConstants.CENTER);
        imageLabel.setBorder(BorderFactory.createLineBorder(Color.GRAY, 2));
        add(imageLabel, BorderLayout.CENTER);

        new DropTarget(imageLabel, new DropTargetAdapter() {
            @Override
            public void drop(DropTargetDropEvent e) {
                try {
                    e.acceptDrop(DnDConstants.ACTION_COPY);
                    @SuppressWarnings("unchecked")
                    List<File> files = (List<File>) e.getTransferable()
                            .getTransferData(DataFlavor.javaFileListFlavor);
                    File file = files.get(0);
                    AppConfig.imgPath = file.getAbsolutePath();
                    Swing.stateJTA.append("[图片] 已加载: " + AppConfig.imgPath + "\n");
                    currentIcon = new ImageIcon(AppConfig.imgPath);
                    scaleAndShow();
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(imageLabel, "拖入图片无效");
                    Swing.exceptionJTA.append("[图片] 拖入失败: " + ex.getMessage() + "\n");
                }
            }
        });

        addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                if (currentIcon != null) scaleAndShow();
            }
        });
    }

    private void scaleAndShow() {
        int w = getWidth() - 5, h = getHeight() - 5;
        if (w <= 0 || h <= 0) return;
        Image scaled = currentIcon.getImage().getScaledInstance(w, h, Image.SCALE_SMOOTH);
        imageLabel.setIcon(new ImageIcon(scaled));
        imageLabel.setText("");
    }
}

// ==================== 操作控制面板 ====================

/** YOLO检测 / AI分析 / 文字对话 / 数据库测试 */
class OperatePanel extends JPanel {

    private JTextField questionField;
    private JTextArea chatArea;

    public OperatePanel() {
        setLayout(new BorderLayout(10, 10));
        setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        JPanel chatPanel = new JPanel(new BorderLayout(5, 5));
        questionField = new JTextField(40);
        questionField.setToolTipText("输入问题，配合上方图片进行 AI 分析");
        chatPanel.add(questionField, BorderLayout.NORTH);

        chatArea = new JTextArea(8, 20);
        chatArea.setLineWrap(true);
        chatArea.setEditable(false);
        chatPanel.add(new JScrollPane(chatArea), BorderLayout.CENTER);

        JPanel btnPanel = new JPanel();
        btnPanel.setLayout(new BoxLayout(btnPanel, BoxLayout.Y_AXIS));
        btnPanel.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 0));
        btnPanel.add(createButton("YOLO 检测", this::runYolo));
        btnPanel.add(Box.createVerticalStrut(5));
        btnPanel.add(createButton("AI 分析", this::runAiAnalyze));
        btnPanel.add(Box.createVerticalStrut(5));
        btnPanel.add(createButton("文字对话", this::runChat));
        btnPanel.add(Box.createVerticalStrut(5));
        btnPanel.add(createButton("数据库测试", this::testDb));
        btnPanel.add(Box.createVerticalGlue());
        btnPanel.add(createButton("清空日志", this::clearLogs));

        add(chatPanel, BorderLayout.CENTER);
        add(btnPanel, BorderLayout.EAST);
    }

    private JButton createButton(String text, Runnable action) {
        JButton btn = new JButton(text);
        btn.addActionListener(e -> action.run());
        return btn;
    }

    private void runYolo() {
        if (AppConfig.imgPath == null || AppConfig.imgPath.isEmpty()) {
            chatArea.setText("⚠ 请先拖入一张图片！"); return;
        }
        Swing.stateJTA.append("[YOLO] 开始检测...\n");
        chatArea.setText("YOLO 检测中...\n");
        Swing.outImgLabel.setText("检测中...");
        new Thread(() -> {
            try {
                long t0 = System.currentTimeMillis();
                List<YoloDetector.Detection> dets = new ArrayList<>();
                String rp = YoloDetector.detect(AppConfig.imgPath, dets);
                long t = System.currentTimeMillis() - t0;
                StringBuilder sb = new StringBuilder();
                sb.append("=== YOLO 检测结果 ===\n耗时: ").append(t).append("ms\n检出: ")
                  .append(dets.size()).append(" 个目标\n\n");
                for (int i = 0; i < dets.size(); i++) {
                    YoloDetector.Detection d = dets.get(i);
                    sb.append(String.format("  %d. %s  %.1f%%  [%d,%d %d,%d]\n",
                        i + 1, d.className, d.confidence * 100,
                        (int) d.x1, (int) d.y1, (int) d.x2, (int) d.y2));
                }
                SwingUtilities.invokeLater(() -> {
                    chatArea.setText(sb.toString());
                    Swing.stateJTA.append("[YOLO] " + t + "ms, " + dets.size() + "个目标\n");
                    Swing.showResultImage(rp);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    chatArea.setText("YOLO 检测失败: " + ex.getMessage());
                    Swing.exceptionJTA.append("[YOLO] " + ex.getMessage() + "\n");
                });
            }
        }).start();
    }

    private void runAiAnalyze() {
        if (AppConfig.imgPath == null || AppConfig.imgPath.isEmpty()) {
            chatArea.setText("⚠ 请先拖入一张图片！"); return;
        }
        String q = questionField.getText().trim();
        if (q.isEmpty()) q = "请分析这张图片中的缺陷情况";
        final String question = q;
        Swing.stateJTA.append("[AI分析] 发送中...\n");
        chatArea.setText("AI 分析中，请稍候...\n");
        new Thread(() -> {
            try {
                long t0 = System.currentTimeMillis();
                // 缩放图片后再发送，避免大图导致内存溢出
                byte[] jpgBytes = resizeAndCompressImage(new File(AppConfig.imgPath));
                String b64 = Base64.getEncoder().encodeToString(jpgBytes);
                String result = OllamaChat.sendImage(AppConfig.url, AppConfig.model, question, b64);
                long t = System.currentTimeMillis() - t0;
                SwingUtilities.invokeLater(() -> {
                    chatArea.setText("=== AI 分析结果 (" + t + "ms) ===\n\n" + result);
                    Swing.stateJTA.append("[AI分析] 完成, " + t + "ms\n");
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    chatArea.setText("AI 分析失败: " + ex.getMessage());
                    Swing.exceptionJTA.append("[AI分析] " + ex.getMessage() + "\n");
                });
            }
        }).start();
    }

    /** 读取图片文件，缩放到最大边长1024并压缩为JPEG */
    private static byte[] resizeAndCompressImage(File file) throws IOException {
        BufferedImage original = ImageIO.read(file);
        if (original == null) throw new IOException("无法读取图片: " + file.getAbsolutePath());

        int w = original.getWidth(), h = original.getHeight();
        int maxSide = 1024;
        BufferedImage out = original;
        if (Math.max(w, h) > maxSide) {
            double scale = (double) maxSide / Math.max(w, h);
            int nw = (int) (w * scale), nh = (int) (h * scale);
            out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(original, 0, 0, nw, nh, null);
            g.dispose();
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(out, "jpg", baos);
        return baos.toByteArray();
    }

    private void runChat() {
        String q = questionField.getText().trim();
        if (q.isEmpty()) { chatArea.setText("⚠ 请输入问题！"); return; }
        final String question = q;
        Swing.stateJTA.append("[对话] 发送: " + q.substring(0, Math.min(30, q.length())) + "...\n");
        chatArea.setText("思考中...\n");
        new Thread(() -> {
            try {
                long t0 = System.currentTimeMillis();
                String answer = OllamaChat.send(AppConfig.url, AppConfig.model, question);
                long t = System.currentTimeMillis() - t0;
                SwingUtilities.invokeLater(() -> {
                    chatArea.setText("=== 对话回复 (" + t + "ms) ===\n\n" + answer);
                    Swing.stateJTA.append("[对话] 完成, " + t + "ms\n");
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    chatArea.setText("对话失败: " + ex.getMessage());
                    Swing.exceptionJTA.append("[对话] " + ex.getMessage() + "\n");
                });
            }
        }).start();
    }

    private void testDb() {
        Swing.stateJTA.append("[数据库] 测试连接...\n");
        chatArea.setText("测试数据库连接...\n");
        new Thread(() -> {
            try {
                java.sql.Connection con = java.sql.DriverManager.getConnection(
                    "jdbc:h2:file:./data/bridge;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
                    "sa", "");
                java.sql.Statement stmt = con.createStatement();
                java.sql.ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM users");
                rs.next(); int users = rs.getInt(1);
                rs = stmt.executeQuery("SELECT COUNT(*) FROM defects");
                rs.next(); int defects = rs.getInt(1);
                con.close();
                SwingUtilities.invokeLater(() -> {
                    chatArea.setText("=== 数据库连接测试 ===\n\n✅ H2 连接成功\n"
                        + "users 表: " + users + " 条记录\n"
                        + "defects 表: " + defects + " 条记录\n");
                    Swing.stateJTA.append("[数据库] ✅ users:" + users + " defects:" + defects + "\n");
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    chatArea.setText("数据库连接失败: " + ex.getMessage());
                    Swing.exceptionJTA.append("[数据库] " + ex.getMessage() + "\n");
                });
            }
        }).start();
    }

    private void clearLogs() {
        Swing.stateJTA.setText("");
        Swing.exceptionJTA.setText("");
        chatArea.setText("");
    }
}
