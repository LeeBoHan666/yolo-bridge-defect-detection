

import javafx.animation.*;
import javafx.application.Platform;
import javafx.geometry.*;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.scene.text.Text;
import javafx.stage.*;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI 对话场景
 *
 * 提供 AI 对话、图片分析、YOLO 检测、数据库自然语言检索功能。
 * 支持粘贴/拖入图片，流式显示 AI 回复。
 *
 * @author 长沙理工大学
 */
public class ChatScene {

    // 外部依赖
    private final DBManager dbManager;
    private final ChatHistory chatHistory;

    // UI 组件
    private Pane root;
    private VBox chatBox;
    private HBox previewBox;
    private Button sendBtn;
    private TextField inputField;
    private Label saveHint;

    // 状态
    private final List<Image> pendingImages = new ArrayList<>();
    private boolean dbSearchMode = false;
    private volatile boolean isGenerating = false;
    private volatile boolean cancelRequested = false;
    private final AtomicBoolean cancelFlag = new AtomicBoolean(false);

    private final boolean isAdmin;

    public ChatScene(DBManager dbManager, ChatHistory chatHistory) {
        this(dbManager, chatHistory, null);
    }

    public ChatScene(DBManager dbManager, ChatHistory chatHistory, String currentRole) {
        this.dbManager = dbManager;
        this.chatHistory = chatHistory;
        this.isAdmin = "admin".equals(currentRole);
        buildUI();
    }

    public Pane getRoot() { return root; }

    // ==================== UI 构建 ====================

    private void buildUI() {
        BorderPane pane = new BorderPane();
        pane.setStyle("-fx-background-color: #ffffff;");

        // 顶部：标题 + 提示
        Label title = new Label("AI 对话助手");
        title.setStyle("-fx-font-size: 20; -fx-font-weight:bold; -fx-padding:15 15 5 15;");

        saveHint = new Label("对话记录自动保存至 aioutput/chat/");
        saveHint.setStyle("-fx-font-size:11; -fx-text-fill:#aaa; -fx-padding:0 15 8 15;");

        HBox hintBar = new HBox(8);
        hintBar.setAlignment(Pos.CENTER_RIGHT);

        Button historyBtn = new Button("历史");
        historyBtn.setStyle("-fx-background-color:transparent; -fx-text-fill:#aaa; -fx-font-size:11; -fx-padding:2 8; -fx-cursor:hand;");
        historyBtn.setOnAction(e -> showHistoryDialog());

        Button clearBtn = new Button("清空");
        clearBtn.setStyle("-fx-background-color:transparent; -fx-text-fill:#aaa; -fx-font-size:11; -fx-padding:2 8; -fx-cursor:hand;");
        clearBtn.setOnAction(e -> chatBox.getChildren().clear());

        hintBar.getChildren().addAll(historyBtn, clearBtn, saveHint);
        VBox topBar = new VBox(0, title, hintBar, new Separator());
        pane.setTop(topBar);

        // 中部：对话区域
        chatBox = new VBox(12);
        chatBox.setPadding(new Insets(15));
        chatBox.setFillWidth(true);
        ScrollPane scrollPane = new ScrollPane(chatBox);
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background-color: #fafafa;");
        VBox.setVgrow(scrollPane, Priority.ALWAYS);
        pane.setCenter(scrollPane);

        // 底部：输入区
        pane.setBottom(buildInputArea());

        // 拖入图片
        pane.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles()) e.acceptTransferModes(TransferMode.COPY);
        });
        pane.setOnDragDropped(e -> {
            for (File file : e.getDragboard().getFiles()) {
                String name = file.getName().toLowerCase();
                if (name.endsWith(".png") || name.endsWith(".jpg")
                    || name.endsWith(".jpeg") || name.endsWith(".gif")) {
                    addPreviewImage(new Image(file.toURI().toString()));
                }
            }
        });

        root = pane;
    }

    /** 构建底部输入区域 */
    private VBox buildInputArea() {
        VBox bottomArea = new VBox(12);
        bottomArea.setPadding(new Insets(15, 20, 20, 20));

        // 图片预览行
        previewBox = new HBox(8);
        previewBox.setStyle("-fx-padding:5;");
        previewBox.setVisible(false);

        // 输入栏
        HBox inputBar = new HBox(10);
        inputField = new TextField();
        inputField.setPromptText("输入内容，粘贴/拖入/选择图片，回车发送...");
        inputField.setStyle("-fx-padding:10; -fx-font-size:14;");
        HBox.setHgrow(inputField, Priority.ALWAYS);

        Button imgBtn = new Button("📂");
        imgBtn.setStyle("-fx-font-size:16; -fx-padding:8 10;");
        imgBtn.setOnAction(e -> openFileChooser());

        sendBtn = new Button("发送");
        sendBtn.setStyle("-fx-background-color:#00adb5; -fx-text-fill:white; -fx-padding:8 16; -fx-font-size:14;");

        inputField.setOnAction(e -> handleSendOrCancel());
        sendBtn.setOnAction(e -> handleSendOrCancel());

        inputBar.getChildren().addAll(inputField, imgBtn, sendBtn);

        // 数据库检索模式切换
        HBox dbBar = new HBox(10);
        dbBar.setAlignment(Pos.CENTER_LEFT);
        Button dbToggleBtn = new Button("🔍 数据库检索");
        dbToggleBtn.setStyle("-fx-background-color: #4a6572; -fx-text-fill: white; -fx-padding:6 14; -fx-font-size:13;");
        dbToggleBtn.setOnAction(e -> toggleDbMode(dbToggleBtn));
        dbBar.getChildren().add(dbToggleBtn);

        // 粘贴图片检测
        inputField.setOnKeyPressed(e -> {
            if (e.isControlDown() && e.getCode().getName().equals("V")) {
                Clipboard clipboard = Clipboard.getSystemClipboard();
                if (clipboard.hasImage()) {
                    addPreviewImage(clipboard.getImage());
                    e.consume();
                }
            }
        });

        bottomArea.getChildren().addAll(previewBox, inputBar, dbBar);
        return bottomArea;
    }

    /** 切换数据库检索模式 */
    private void toggleDbMode(Button btn) {
        dbSearchMode = !dbSearchMode;
        if (dbSearchMode) {
            btn.setText("🔍 数据库检索模式 (开启中)");
            btn.setStyle("-fx-background-color: #00adb5; -fx-text-fill: white; -fx-padding:6 14; -fx-font-size:13;");
            inputField.setPromptText("数据库检索模式 — 输入自然语言查询，回车发送...");
            if (!isGenerating) setSendButtonForDbMode(true);
        } else {
            btn.setText("🔍 数据库检索");
            btn.setStyle("-fx-background-color: #4a6572; -fx-text-fill: white; -fx-padding:6 14; -fx-font-size:13;");
            inputField.setPromptText("输入内容，粘贴/拖入/选择图片，回车发送...");
            if (!isGenerating) setSendButtonForDbMode(false);
        }
    }

    // ==================== 发送逻辑 ====================

    private void handleSendOrCancel() {
        if (isGenerating) {
            cancelRequested = true;
            cancelFlag.set(true);
        } else {
            sendMessage();
        }
    }

    private void sendMessage() {
        String msg = inputField.getText().trim();
        if (msg.isEmpty() && pendingImages.isEmpty()) return;

        // 数据库检索模式
        if (dbSearchMode && !msg.isEmpty() && pendingImages.isEmpty()) {
            inputField.clear();
            executeDbQuery(msg);
            return;
        }

        addUserMsgWithImage(msg, pendingImages);

        final boolean hasImage = !pendingImages.isEmpty();
        final List<Image> imagesToProcess = new ArrayList<>(pendingImages);
        final Image firstImage = hasImage ? imagesToProcess.get(0) : null;
        final int imageCount = imagesToProcess.size();

        if (hasImage || !msg.isEmpty()) {
            String entry = msg;
            if (hasImage) {
                entry = (msg.isEmpty() ? "" : msg + "\n\n")
                      + (imageCount > 1 ? "[图片×" + imageCount + "]" : "[图片]");
            }
            chatHistory.append("user", entry);
        }

        inputField.clear();
        pendingImages.clear();
        previewBox.getChildren().clear();
        previewBox.setVisible(false);

        HBox thinkingBubble = addThinkingBubble();

        new Thread(() -> {
            try {
                if (hasImage) {
                    processWithImage(msg, imagesToProcess, firstImage, imageCount, thinkingBubble);
                } else {
                    processTextOnly(msg, thinkingBubble);
                }
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    setGeneratingUI(false);
                    stopThinkingAnim(thinkingBubble);
                    chatBox.getChildren().remove(thinkingBubble);
                    addAiBubble("错误: " + ex.getMessage());
                });
            }
        }).start();
    }

    /** 处理带图片的消息：YOLO检测 → 展示结果 → AI分析 */
    private void processWithImage(String msg, List<Image> imagesToProcess,
                                   Image firstImage, int imageCount,
                                   HBox thinkingBubble) throws Exception {
        isGenerating = true;
        cancelRequested = false;
        cancelFlag.set(false);
        Platform.runLater(() -> setGeneratingUI(true));

        // ===== 阶段1：保存临时文件 =====
        List<File> tempFiles = new ArrayList<>();
        for (Image img : imagesToProcess) {
            tempFiles.add(saveTempImage(img));
        }

        // ===== 阶段2：并行 YOLO 检测 =====
        updateThinkingText(thinkingBubble, "YOLO 检测中，首次模型加载约需30秒...");

        int total = imagesToProcess.size();
        List<String> resultPaths = Collections.synchronizedList(new ArrayList<>());
        List<List<YoloDetector.Detection>> allDetections = Collections.synchronizedList(new ArrayList<>());
        List<Integer> savedDetectionIds = Collections.synchronizedList(new ArrayList<>());
        StringBuilder yoloSummary = new StringBuilder();

        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            final int idx = i;
            final File tempFile = tempFiles.get(i);
            Thread t = new Thread(() -> {
                try {
                    List<YoloDetector.Detection> dets = new ArrayList<>();
                    String rp = YoloDetector.detect(tempFile.getAbsolutePath(), dets);
                    if (cancelRequested) return;
                    if (!dets.isEmpty()) {
                        int detId = saveDetectionToDb(tempFile.getAbsolutePath(), rp, dets);
                        if (detId > 0) savedDetectionIds.add(detId);
                    }
                    synchronized (yoloSummary) {
                        if (total > 1) yoloSummary.append("图片").append(idx + 1).append(": ");
                        if (!dets.isEmpty()) {
                            yoloSummary.append(dets.size()).append("处缺陷 (");
                            Map<String, Integer> typeCount = new LinkedHashMap<>();
                            for (YoloDetector.Detection d : dets)
                                typeCount.merge(d.className, 1, Integer::sum);
                            boolean first = true;
                            for (Map.Entry<String, Integer> e : typeCount.entrySet()) {
                                if (!first) yoloSummary.append("、");
                                yoloSummary.append(e.getKey()).append("×").append(e.getValue());
                                first = false;
                            }
                            yoloSummary.append(")\n");
                        } else {
                            yoloSummary.append("未检测到\n");
                        }
                        resultPaths.add(rp);
                        allDetections.add(dets);
                    }
                } catch (Exception ex) {
                    synchronized (yoloSummary) {
                        if (total > 1) yoloSummary.append("图片").append(idx + 1).append(": ");
                        yoloSummary.append("检测失败: ").append(ex.getMessage()).append("\n");
                    }
                }
            });
            t.setDaemon(true);
            threads.add(t);
            t.start();
        }

        for (Thread t : threads) {
            try { t.join(300_000); } catch (InterruptedException ignored) {}
        }

        if (cancelRequested) {
            Platform.runLater(() -> {
                setGeneratingUI(false);
                stopThinkingAnim(thinkingBubble);
                chatBox.getChildren().remove(thinkingBubble);
            });
            return;
        }

        // ===== 阶段3：展示 YOLO 结果 =====
        int totalDefects = allDetections.stream().mapToInt(List::size).sum();
        String header = total > 1
            ? "YOLO 批量检测结果（" + total + " 张图片，共 " + totalDefects + " 处缺陷）:\n"
            : "YOLO 检测结果:\n";
        String yoloText = header + yoloSummary;
        chatHistory.append("ai", yoloText);

        Platform.runLater(() -> {
            stopThinkingAnim(thinkingBubble);
            chatBox.getChildren().remove(thinkingBubble);
            if (total == 1 && !resultPaths.isEmpty()) {
                String rp = resultPaths.get(0);
                Image yoloImg = (rp != null && !rp.isEmpty())
                    ? new Image(new File(rp).toURI().toString()) : null;
                addAiBubbleWithImage(yoloText, yoloImg);
            } else {
                addAiBubble(yoloText);
                for (String rp : resultPaths) {
                    if (rp != null && !rp.isEmpty()) {
                        addAiBubbleWithImage("", new Image(new File(rp).toURI().toString()));
                    }
                }
            }
        });

        // ===== 阶段4：AI 分析（有文字 + 未取消 时才执行） =====
        if (msg.isEmpty() || cancelRequested) {
            Platform.runLater(() -> setGeneratingUI(false));
            return;
        }

        // 在 FX 线程创建新的思考气泡
        FutureTask<HBox> bubbleTask = new FutureTask<>(() -> addThinkingBubble());
        Platform.runLater(bubbleTask);
        final HBox aiBubble = bubbleTask.get();
        updateThinkingText(aiBubble, "AI 分析中...");

        try {
            // 缩放 + JPEG 压缩，大幅减少内存和网络传输量
            byte[] jpgBytes = javafxImageToJpgBytes(firstImage);
            String b64 = Base64.getEncoder().encodeToString(jpgBytes);
            String analysis = OllamaChat.sendImage(AppConfig.url, AppConfig.model, msg, b64);

            if (!cancelRequested) {
                // 将 AI 分析结果写入本次所有检测记录
                if (dbManager.isConnected()) {
                    for (int detId : savedDetectionIds) {
                        try { dbManager.updateAiAnalysis(detId, analysis); }
                        catch (Exception ignored) {}
                    }
                }
                chatHistory.append("ai", "AI 分析:\n" + analysis);
                Platform.runLater(() -> {
                    stopThinkingAnim(aiBubble);
                    chatBox.getChildren().remove(aiBubble);
                    addAiBubble("AI 分析:\n" + analysis);
                });
            } else {
                Platform.runLater(() -> {
                    stopThinkingAnim(aiBubble);
                    chatBox.getChildren().remove(aiBubble);
                });
            }
        } catch (Exception ex) {
            Platform.runLater(() -> {
                stopThinkingAnim(aiBubble);
                chatBox.getChildren().remove(aiBubble);
                addAiBubble("AI 分析失败: " + ex.getMessage());
            });
        }

        Platform.runLater(() -> setGeneratingUI(false));
    }

    /** 处理纯文本消息：流式AI对话或SQL查询 */
    private void processTextOnly(String msg, HBox thinkingBubble) throws Exception {
        if (dbManager.isConnected() && isDbQuery(msg)) {
            String answer = executeAiSql(msg);
            chatHistory.append("ai", answer);
            Platform.runLater(() -> {
                stopThinkingAnim(thinkingBubble);
                chatBox.getChildren().remove(thinkingBubble);
                addAiBubble(answer);
            });
            return;
        }

        // 流式对话
        isGenerating = true;
        cancelRequested = false;
        cancelFlag.set(false);
        Platform.runLater(() -> setGeneratingUI(true));

        StringBuilder fullAnswer = new StringBuilder();
        Label[] holder = new Label[1];

        String answer = OllamaChat.sendStream(AppConfig.url, AppConfig.model, msg, token -> {
            Platform.runLater(() -> {
                if (holder[0] == null) {
                    stopThinkingAnim(thinkingBubble);
                    chatBox.getChildren().remove(thinkingBubble);
                    VBox bubble = createStreamingBubble();
                    holder[0] = (Label) bubble.getChildren().get(0);
                }
                if (holder[0] != null) {
                    holder[0].setText(holder[0].getText() + token);
                }
            });
            fullAnswer.append(token);
        }, cancelFlag);

        final String finalAnswer = cancelRequested && fullAnswer.length() == 0
            ? "(已取消)" : fullAnswer.toString();
        chatHistory.append("ai", finalAnswer);

        Platform.runLater(() -> {
            setGeneratingUI(false);
            if (holder[0] != null) {
                VBox bubble = (VBox) holder[0].getParent();
                HBox parent = (HBox) bubble.getParent();
                parent.getChildren().remove(bubble);
                chatBox.getChildren().remove(parent);
                addAiBubble(finalAnswer);
            } else {
                stopThinkingAnim(thinkingBubble);
                chatBox.getChildren().remove(thinkingBubble);
                addAiBubble(finalAnswer);
            }
        });
    }

    // ==================== UI 辅助方法 ====================

    private void setGeneratingUI(boolean generating) {
        isGenerating = generating;
        if (generating) {
            sendBtn.setText("停止");
            sendBtn.setStyle("-fx-background-color:#e74c3c; -fx-text-fill:white; -fx-padding:8 16; -fx-font-size:14;");
        } else {
            setSendButtonForDbMode(dbSearchMode);
        }
    }

    private void setSendButtonForDbMode(boolean dbMode) {
        if (dbMode) {
            sendBtn.setText("检索");
            sendBtn.setStyle("-fx-background-color:#4a90d9; -fx-text-fill:white; -fx-padding:8 16; -fx-font-size:14;");
        } else {
            sendBtn.setText("发送");
            sendBtn.setStyle("-fx-background-color:#00adb5; -fx-text-fill:white; -fx-padding:8 16; -fx-font-size:14;");
        }
    }

    // ==================== 气泡组件 ====================

    private HBox addThinkingBubble() {
        HBox hBox = new HBox();
        hBox.setMaxWidth(Double.MAX_VALUE);
        hBox.setAlignment(Pos.CENTER_LEFT);

        Label label = new Label("思考中");
        label.setWrapText(true);
        label.setMaxWidth(650);
        label.setStyle("-fx-background-color:#eeeeee; -fx-text-fill:#888; "
                     + "-fx-padding:10 14; -fx-background-radius:14; -fx-font-size:14;");

        Timeline anim = new Timeline(
            new KeyFrame(Duration.ZERO,         e -> label.setText("思考中")),
            new KeyFrame(Duration.millis(400),  e -> label.setText("思考中.")),
            new KeyFrame(Duration.millis(800),  e -> label.setText("思考中..")),
            new KeyFrame(Duration.millis(1200), e -> label.setText("思考中..."))
        );
        anim.setCycleCount(Timeline.INDEFINITE);
        anim.play();

        hBox.setUserData(anim);
        hBox.getChildren().add(label);
        chatBox.getChildren().add(hBox);
        return hBox;
    }

    /** 更新思考气泡的文字（停止动画后设置静态文本） */
    private void updateThinkingText(HBox bubble, String text) {
        Platform.runLater(() -> {
            stopThinkingAnim(bubble);
            Label label = (Label) bubble.getChildren().get(0);
            label.setText(text);
        });
    }

    private void stopThinkingAnim(HBox bubble) {
        Timeline anim = (Timeline) bubble.getUserData();
        if (anim != null) anim.stop();
    }

    private VBox createStreamingBubble() {
        HBox hBox = new HBox(4);
        hBox.setMaxWidth(Double.MAX_VALUE);
        hBox.setAlignment(Pos.CENTER_LEFT);

        VBox bubble = new VBox(0);
        bubble.setStyle("-fx-background-color:#eeeeee; -fx-background-radius:14; -fx-padding:10 14;");

        Label label = new Label("");
        label.setWrapText(true);
        label.setMaxWidth(650);
        label.setStyle("-fx-text-fill:#222; -fx-font-size:14;");

        bubble.getChildren().add(label);
        hBox.getChildren().add(bubble);
        chatBox.getChildren().add(hBox);
        return bubble;
    }

    private void addUserMsgWithImage(String text, List<Image> images) {
        HBox hBox = new HBox();
        hBox.setMaxWidth(Double.MAX_VALUE);
        hBox.setAlignment(Pos.CENTER_RIGHT);

        VBox content = new VBox(8);
        content.setStyle("-fx-background-color:#00adb5; -fx-background-radius:14; -fx-padding:10 14;");

        if (!text.isEmpty()) {
            Label label = new Label(text);
            label.setWrapText(true);
            label.setMaxWidth(650);
            label.setStyle("-fx-text-fill:white; -fx-font-size:14;");
            content.getChildren().add(label);
        }

        if (!images.isEmpty()) {
            HBox imgBox = new HBox(8);
            for (Image img : images) {
                ImageView iv = new ImageView(img);
                iv.setPreserveRatio(true);
                iv.setFitWidth(200);
                imgBox.getChildren().add(iv);
            }
            content.getChildren().add(imgBox);
        }

        hBox.getChildren().add(content);
        chatBox.getChildren().add(hBox);
    }

    void addAiBubble(String text) {
        HBox hBox = new HBox(4);
        hBox.setMaxWidth(Double.MAX_VALUE);
        hBox.setAlignment(Pos.CENTER_LEFT);

        VBox bubble = new VBox(0);
        bubble.setStyle("-fx-background-color:#eeeeee; -fx-background-radius:14; -fx-padding:10 14;");

        Label label = new Label(text);
        label.setWrapText(true);
        label.setMaxWidth(650);
        label.setStyle("-fx-text-fill:#222; -fx-font-size:14;");

        HBox btnBar = new HBox(6);
        btnBar.setAlignment(Pos.CENTER_RIGHT);

        Button copyBtn = new Button("复制");
        copyBtn.setStyle("-fx-background-color:transparent; -fx-text-fill:#999; "
                        + "-fx-font-size:10; -fx-padding:2 4; -fx-cursor:hand;");
        copyBtn.setOnAction(ev -> {
            ClipboardContent cc = new ClipboardContent();
            cc.putString(text);
            Clipboard.getSystemClipboard().setContent(cc);
            copyBtn.setText("已复制");
            new Thread(() -> {
                try { Thread.sleep(1500); } catch (Exception ignored) {}
                Platform.runLater(() -> copyBtn.setText("复制"));
            }).start();
        });
        btnBar.getChildren().add(copyBtn);

        // 如果包含 SQL，添加复制SQL按钮
        if (text.contains("SQL: ")) {
            Button sqlBtn = new Button("复制SQL");
            sqlBtn.setStyle("-fx-background-color:transparent; -fx-text-fill:#4a90d9; "
                          + "-fx-font-size:10; -fx-padding:2 4; -fx-cursor:hand;");
            sqlBtn.setOnAction(ev -> {
                String sql = extractSqlFromText(text);
                ClipboardContent cc = new ClipboardContent();
                cc.putString(sql);
                Clipboard.getSystemClipboard().setContent(cc);
                sqlBtn.setText("已复制");
                new Thread(() -> {
                    try { Thread.sleep(1500); } catch (Exception ignored) {}
                    Platform.runLater(() -> sqlBtn.setText("复制SQL"));
                }).start();
            });
            btnBar.getChildren().add(sqlBtn);
        }

        // 数据库检索结果添加导出CSV
        if (text.startsWith("📊 数据库检索结果:")) {
            Button csvBtn = new Button("导出CSV");
            csvBtn.setStyle("-fx-background-color:transparent; -fx-text-fill:#27ae60; "
                          + "-fx-font-size:10; -fx-padding:2 4; -fx-cursor:hand;");
            csvBtn.setOnAction(ev -> exportDbResultCsv(text));
            btnBar.getChildren().add(csvBtn);
        }

        bubble.getChildren().addAll(label, btnBar);
        hBox.getChildren().add(bubble);
        chatBox.getChildren().add(hBox);
    }

    private void addAiBubbleWithImage(String text, Image image) {
        HBox hBox = new HBox(4);
        hBox.setMaxWidth(Double.MAX_VALUE);
        hBox.setAlignment(Pos.CENTER_LEFT);

        VBox bubble = new VBox(6);
        bubble.setStyle("-fx-background-color:#eeeeee; -fx-background-radius:14; -fx-padding:10 14;");

        if (image != null) {
            ImageView iv = new ImageView(image);
            iv.setPreserveRatio(true);
            iv.setFitWidth(400);
            iv.setStyle("-fx-cursor:hand;");
            iv.setOnMouseClicked(ev -> showImagePopup(image));
            bubble.getChildren().add(iv);
        }

        if (!text.isEmpty()) {
            Label label = new Label(text);
            label.setWrapText(true);
            label.setMaxWidth(650);
            label.setStyle("-fx-text-fill:#222; -fx-font-size:14;");
            bubble.getChildren().add(label);
        }

        Button copyBtn = new Button("复制");
        copyBtn.setStyle("-fx-background-color:transparent; -fx-text-fill:#999; "
                        + "-fx-font-size:10; -fx-padding:2 4; -fx-cursor:hand;");
        copyBtn.setOnAction(ev -> {
            ClipboardContent cc = new ClipboardContent();
            cc.putString(text);
            Clipboard.getSystemClipboard().setContent(cc);
            copyBtn.setText("已复制");
            new Thread(() -> {
                try { Thread.sleep(1500); } catch (Exception ignored) {}
                Platform.runLater(() -> copyBtn.setText("复制"));
            }).start();
        });
        bubble.getChildren().add(new HBox(copyBtn));

        hBox.getChildren().add(bubble);
        chatBox.getChildren().add(hBox);
    }

    // ==================== 图片预览 ====================

    void addPreviewImage(Image img) {
        pendingImages.add(img);
        previewBox.setVisible(true);

        StackPane container = new StackPane();

        ImageView iv = new ImageView(img);
        iv.setPreserveRatio(true);
        iv.setFitHeight(60);
        iv.setStyle("-fx-border-radius:6; -fx-padding:2; -fx-cursor:hand;");
        iv.setOnMouseClicked(ev -> showImagePopup(img));

        Button closeBtn = new Button("×");
        closeBtn.setStyle("-fx-background-color:rgba(0,0,0,0.5); -fx-text-fill:white; "
                        + "-fx-font-size:9; -fx-padding:0 4; -fx-background-radius:8;");
        closeBtn.setOnAction(ev -> {
            pendingImages.remove(img);
            previewBox.getChildren().remove(container);
            if (previewBox.getChildren().isEmpty()) previewBox.setVisible(false);
        });

        container.getChildren().addAll(iv, closeBtn);
        StackPane.setAlignment(closeBtn, Pos.TOP_RIGHT);
        StackPane.setMargin(closeBtn, new Insets(2, 2, 0, 0));

        previewBox.getChildren().add(container);
    }

    private void showImagePopup(Image img) {
        Stage popup = new Stage();
        popup.setTitle("图片预览");
        popup.initModality(Modality.NONE);

        ImageView iv = new ImageView(img);
        iv.setPreserveRatio(true);

        ScrollPane sp = new ScrollPane(iv);
        sp.setStyle("-fx-background-color:#111;");
        sp.setPannable(true);

        double maxW = 1000, maxH = 750;
        double w = Math.min(img.getWidth(), maxW);
        double h = Math.min(img.getHeight(), maxH);
        double ratio = img.getWidth() / img.getHeight();
        if (w / ratio > h) w = h * ratio; else h = w / ratio;
        iv.setFitWidth(w);
        iv.setFitHeight(h);

        Scene scene = new Scene(sp, w + 20, h + 20);
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) popup.close();
        });
        popup.setScene(scene);
        popup.show();
    }

    private void openFileChooser() {
        FileChooser chooser = new FileChooser();
        chooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("图片", "*.png", "*.jpg", "*.jpeg", "*.gif"));
        File file = chooser.showOpenDialog(null);
        if (file != null) {
            addPreviewImage(new Image(file.toURI().toString()));
        }
    }

    // ==================== 对话历史 ====================

    private void showHistoryDialog() {
        List<File> allFiles = chatHistory.listSessions();
        if (allFiles.isEmpty()) {
            FX.showAlert(Alert.AlertType.INFORMATION, "提示", "暂无历史对话记录");
            return;
        }

        TextField searchField = new TextField();
        searchField.setPromptText("搜索历史对话内容...");
        searchField.setStyle("-fx-padding:8; -fx-font-size:13;");

        ListView<String> listView = new ListView<>();
        listView.setPrefHeight(300);

        List<File> filteredFiles = new ArrayList<>(allFiles);
        for (File f : filteredFiles) listView.getItems().add(f.getName().replace(".md", ""));

        searchField.textProperty().addListener((obs, old, kw) -> {
            listView.getItems().clear();
            filteredFiles.clear();
            String keyword = kw.trim().toLowerCase();
            if (keyword.isEmpty()) {
                filteredFiles.addAll(allFiles);
                for (File f : filteredFiles) listView.getItems().add(f.getName().replace(".md", ""));
            } else {
                for (File f : allFiles) {
                    if (f.getName().toLowerCase().contains(keyword)) {
                        filteredFiles.add(f);
                        listView.getItems().add(f.getName().replace(".md", ""));
                    } else {
                        try {
                            if (chatHistory.load(f).toLowerCase().contains(keyword)) {
                                filteredFiles.add(f);
                                listView.getItems().add("🔍 " + f.getName().replace(".md", ""));
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }
            if (listView.getItems().isEmpty()) listView.getItems().add("(无匹配结果)");
        });

        Button openBtn = new Button("加载选中对话");
        openBtn.setStyle("-fx-background-color:#00adb5; -fx-text-fill:white;");

        VBox content = new VBox(10,
            new Label("历史对话列表（共 " + allFiles.size() + " 条）："),
            searchField, listView, openBtn);
        Stage dlg = new Stage();
        dlg.setTitle("历史对话搜索");
        dlg.initModality(Modality.APPLICATION_MODAL);
        dlg.setScene(new Scene(content, 480, 480));

        openBtn.setOnAction(e -> {
            int idx = listView.getSelectionModel().getSelectedIndex();
            if (idx < 0 || idx >= filteredFiles.size()) return;
            try {
                File f = filteredFiles.get(idx);
                chatHistory.newSession();
                chatBox.getChildren().clear();
                String history = chatHistory.load(f);
                addAiBubble("已加载历史对话: " + f.getName().replace(".md", "") + "\n\n" + history);
            } catch (Exception ex) { FX.showAlert(Alert.AlertType.ERROR, "错误", "加载失败"); }
            dlg.close();
        });
        dlg.showAndWait();
    }

    // ==================== 数据库检索 ====================

    private boolean isDbQuery(String msg) {
        String m = msg.toLowerCase();
        return m.startsWith("查询") || m.startsWith("/sql") || m.startsWith("sql:")
            || m.startsWith("统计") || m.startsWith("列出") || m.startsWith("检索")
            || m.startsWith("select ");
    }

    private void executeDbQuery(String query) {
        if (!dbManager.isConnected()) {
            addAiBubble("⚠️ 数据库未连接，请先前往设置页面连接数据库。");
            return;
        }

        addUserMsgWithImage("🔍 数据库查询: " + query, Collections.emptyList());
        chatHistory.append("user", "[数据库查询] " + query);

        isGenerating = true;
        cancelRequested = false;
        cancelFlag.set(false);
        Platform.runLater(() -> setGeneratingUI(true));

        HBox thinkingBubble = addThinkingBubble();

        new Thread(() -> {
            try {
                if (cancelRequested) {
                    Platform.runLater(() -> {
                        setGeneratingUI(false);
                        stopThinkingAnim(thinkingBubble);
                        chatBox.getChildren().remove(thinkingBubble);
                    });
                    return;
                }
                String result = executeAiSql(query);
                if (cancelRequested) {
                    Platform.runLater(() -> {
                        setGeneratingUI(false);
                        stopThinkingAnim(thinkingBubble);
                        chatBox.getChildren().remove(thinkingBubble);
                    });
                    return;
                }
                chatHistory.append("ai", result);
                Platform.runLater(() -> {
                    setGeneratingUI(false);
                    stopThinkingAnim(thinkingBubble);
                    chatBox.getChildren().remove(thinkingBubble);
                    addAiBubble("📊 数据库检索结果:\n\n" + result);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    setGeneratingUI(false);
                    stopThinkingAnim(thinkingBubble);
                    chatBox.getChildren().remove(thinkingBubble);
                    addAiBubble("❌ 查询失败: " + ex.getMessage());
                });
            }
        }).start();
    }

    private String executeAiSql(String userQuestion) throws Exception {
        // 收集数据库 schema 信息
        StringBuilder schema = new StringBuilder();
        for (String table : dbManager.getTables()) {
            if (!isAdmin && table.equalsIgnoreCase("USERS")) continue;
            schema.append("表 ").append(table).append(" (");
            List<String> cols = dbManager.getColumns(table);
            for (int i = 0; i < cols.size(); i++) {
                if (i > 0) schema.append(", ");
                schema.append(cols.get(i));
            }
            schema.append(")\n");
        }

        String prompt = "你是一个H2/MySQL数据库查询助手。根据以下数据库表结构和用户问题，只生成一条SELECT查询语句"
            + "（不要INSERT/UPDATE/DELETE/DROP）。用中文列别名让结果易读。"
            + "只输出SQL，不要解释，不要markdown代码块。\n\n"
            + "数据库结构:\n" + schema + "\n用户问题: " + userQuestion;

        String sql = OllamaChat.send(AppConfig.url, AppConfig.model, prompt).trim()
            .replace("`", "").replace("；", ";");

        // 提取 SQL
        if (sql.contains("SELECT ")) {
            int start = sql.indexOf("SELECT ");
            int end = sql.lastIndexOf(";");
            if (end < 0) end = sql.length();
            sql = sql.substring(start, end).trim();
            if (!sql.endsWith(";")) sql += ";";
        }
        if (!sql.toUpperCase().startsWith("SELECT")) {
            return "AI 生成的语句不是有效的 SELECT 查询:\n" + sql;
        }

        // 执行查询
        List<Map<String, Object>> rows = dbManager.query(sql);
        if (rows.isEmpty()) return "查询结果为空\nSQL: " + sql;
        if (rows.size() > 100) rows = rows.subList(0, 100);

        // 格式化为表格
        return formatResultTable(rows, sql);
    }

    /** 将查询结果格式化为对齐的文本表格 */
    private String formatResultTable(List<Map<String, Object>> rows, String sql) {
        StringBuilder result = new StringBuilder("SQL: ").append(sql).append("\n\n");
        result.append("共 ").append(rows.size()).append(" 条结果:\n");

        Map<String, Object> first = rows.get(0);
        List<String> colNames = new ArrayList<>(first.keySet());

        int[] widths = new int[colNames.size()];
        for (int i = 0; i < colNames.size(); i++) widths[i] = colNames.get(i).length();
        for (Map<String, Object> row : rows) {
            for (int i = 0; i < colNames.size(); i++) {
                Object v = row.get(colNames.get(i));
                int len = v == null ? 4 : v.toString().length();
                if (len > widths[i]) widths[i] = Math.min(len, 25);
            }
        }

        // 表头
        StringBuilder sep = new StringBuilder();
        for (int i = 0; i < colNames.size(); i++) {
            result.append(String.format("%-" + widths[i] + "s  ", colNames.get(i)));
            sep.append("-".repeat(widths[i])).append("  ");
        }
        result.append("\n").append(sep).append("\n");

        // 数据行
        for (Map<String, Object> row : rows) {
            for (int i = 0; i < colNames.size(); i++) {
                Object v = row.get(colNames.get(i));
                String s = v == null ? "NULL" : v.toString();
                if (s.length() > widths[i]) s = s.substring(0, widths[i] - 2) + "..";
                result.append(String.format("%-" + widths[i] + "s  ", s));
            }
            result.append("\n");
        }
        return result.toString();
    }

    /** 从文本中提取 SQL */
    private String extractSqlFromText(String text) {
        String marker = "SQL: ";
        int start = text.indexOf(marker);
        if (start < 0) return "";
        start += marker.length();
        int end = text.indexOf("\n", start);
        if (end < 0) end = text.length();
        return text.substring(start, end).trim();
    }

    /** 将表格文本导出为 CSV */
    private void exportDbResultCsv(String text) {
        String[] lines = text.split("\n");
        StringBuilder csv = new StringBuilder();
        boolean inTable = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            if (i + 1 < lines.length && lines[i + 1].trim().startsWith("-")) {
                csv.append(line.replaceAll("  +", ",")).append("\n");
                inTable = true;
                i++;
                continue;
            }
            if (inTable && !line.startsWith("SQL:") && !line.startsWith("共 ") && !line.startsWith("📊")) {
                csv.append(line.replaceAll("  +", ",")).append("\n");
            }
        }
        if (csv.length() == 0) {
            FX.showAlert(Alert.AlertType.WARNING, "提示", "未能从结果中提取表格数据");
            return;
        }
        File dir = new File(System.getProperty("user.dir") + "/src/aioutput/report");
        dir.mkdirs();
        File file = new File(dir, "export_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date()) + ".csv");
        try (java.io.FileWriter fw = new java.io.FileWriter(file)) {
            fw.write(csv.toString());
            FX.showAlert(Alert.AlertType.INFORMATION, "导出成功", "已导出至: " + file.getAbsolutePath());
        } catch (IOException ex) {
            FX.showAlert(Alert.AlertType.ERROR, "导出失败", ex.getMessage());
        }
    }

    // ==================== 图片工具 ====================

    File saveTempImage(Image img) throws Exception {
        byte[] pngBytes = javafxImageToPngBytes(img);
        File tempFile = File.createTempFile("fx_img_", ".png");
        try (FileOutputStream fos = new FileOutputStream(tempFile)) {
            fos.write(pngBytes);
        }
        return tempFile;
    }

    byte[] javafxImageToPngBytes(Image img) throws Exception {
        int w = (int) img.getWidth();
        int h = (int) img.getHeight();
        if (w <= 0 || h <= 0) throw new Exception("图片未加载完成，宽高为0，请重新拖入图片后稍等再试");
        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        PixelReader pr = img.getPixelReader();
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                bi.setRGB(x, y, pr.getArgb(x, y));
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(bi, "png", baos);
        return baos.toByteArray();
    }

    /** 将图片缩放并压缩为 JPEG 字节（最大边长1024），大幅减少发送给 Ollama 的内存占用 */
    byte[] javafxImageToJpgBytes(Image img) throws Exception {
        int w = (int) img.getWidth();
        int h = (int) img.getHeight();
        if (w <= 0 || h <= 0) throw new Exception("图片未加载完成，宽高为0");

        // 1. 从 JavaFX Image 读取像素到 BufferedImage
        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        PixelReader pr = img.getPixelReader();
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                bi.setRGB(x, y, pr.getArgb(x, y));

        // 2. 缩放到最大 1024 像素（保持宽高比）
        int maxSide = 1024;
        if (Math.max(w, h) > maxSide) {
            double scale = (double) maxSide / Math.max(w, h);
            int nw = (int) (w * scale), nh = (int) (h * scale);
            BufferedImage resized = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = resized.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(bi, 0, 0, nw, nh, null);
            g.dispose();
            bi = resized;
        }

        // 3. 输出为 JPEG
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(bi, "jpg", baos);
        return baos.toByteArray();
    }

    // ==================== 缺陷记录保存 ====================

    private int saveDetectionToDb(String imagePath, String resultPath,
                                    List<YoloDetector.Detection> detections) {
        if (!dbManager.isConnected()) return -1;
        try {
            dbManager.ensureDefectTable();
            StringBuilder types = new StringBuilder();
            StringBuilder json = new StringBuilder("[");
            for (int i = 0; i < detections.size(); i++) {
                YoloDetector.Detection d = detections.get(i);
                if (i > 0) { types.append(","); json.append(","); }
                types.append(d.className);
                json.append("{\"class\":\"").append(d.className)
                    .append("\",\"conf\":").append(String.format("%.2f", d.confidence))
                    .append(",\"box\":[").append((int)d.x1).append(",").append((int)d.y1)
                    .append(",").append((int)d.x2).append(",").append((int)d.y2).append("]}");
            }
            json.append("]");
            return dbManager.saveDetection(imagePath, resultPath, detections.size(),
                types.toString(), json.toString());
        } catch (Exception ignored) { return -1; }
    }
}
