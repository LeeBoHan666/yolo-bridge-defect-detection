

import javafx.application.Platform;
import javafx.geometry.*;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * 摄像头管理场景
 *
 * 支持添加多个摄像头（MJPEG 流或 HTTP 快照），实时显示画面。
 * 可手动截图触发 YOLO 检测，也可设置定时自动截图。
 *
 * @author 长沙理工大学
 */
public class CameraScene {

    private final DBManager dbManager;
    private final List<CameraStream> cameras;

    // UI 组件
    private Pane root;
    private FlowPane cameraGrid;
    private ScheduledExecutorService autoCaptureExecutor;

    public CameraScene(DBManager dbManager, List<CameraStream> cameras) {
        this.dbManager = dbManager;
        this.cameras = cameras;
        buildUI();
    }

    public Pane getRoot() { return root; }

    /** 停止所有自动截图 */
    public void shutdown() {
        if (autoCaptureExecutor != null) {
            autoCaptureExecutor.shutdown();
            autoCaptureExecutor = null;
        }
    }

    // ==================== UI 构建 ====================

    private void buildUI() {
        VBox pane = new VBox(10);
        pane.setPadding(new Insets(15));
        pane.setStyle("-fx-background-color: white;");

        Label title = new Label("摄像头管理");
        title.setStyle("-fx-font-size:20; -fx-font-weight:bold;");

        // 添加栏
        HBox addBar = new HBox(8);
        TextField urlField = new TextField();
        urlField.setPromptText("输入摄像头 URL（支持 MJPEG / HTTP 快照）...");
        HBox.setHgrow(urlField, Priority.ALWAYS);

        Button addBtn = new Button("添加");
        addBtn.setStyle("-fx-background-color:#00adb5; -fx-text-fill:white;");

        Runnable addCamera = () -> {
            String url = urlField.getText().trim();
            if (url.isEmpty()) return;
            String camName = "摄像头" + (cameras.size() + 1);
            CameraStream cs = new CameraStream(camName, url);
            cameras.add(cs);
            cs.start();
            cameraGrid.getChildren().add(createCameraCard(cs));
            urlField.clear();
        };
        addBtn.setOnAction(e -> addCamera.run());
        urlField.setOnAction(e -> addCamera.run());

        addBar.getChildren().addAll(urlField, addBtn);

        // 自动截图控制
        HBox autoBar = buildAutoCaptureBar();

        // 摄像头网格
        cameraGrid = new FlowPane(12, 12);
        cameraGrid.setStyle("-fx-background-color:#f0f0f0; -fx-padding:10;");
        VBox.setVgrow(cameraGrid, Priority.ALWAYS);

        pane.getChildren().addAll(title, new Separator(), addBar, autoBar, cameraGrid);
        root = pane;
    }

    /** 自动截图控制栏 */
    private HBox buildAutoCaptureBar() {
        Label intervalLabel = new Label("自动截图间隔(秒):");
        TextField intervalField = new TextField("5");
        intervalField.setPrefWidth(50);

        Button startBtn = new Button("▶ 开始自动");
        startBtn.setStyle("-fx-background-color:#28a745; -fx-text-fill:white;");
        Button stopBtn = new Button("■ 停止");
        stopBtn.setStyle("-fx-background-color:#dc3545; -fx-text-fill:white;");
        stopBtn.setDisable(true);

        startBtn.setOnAction(e -> {
            int interval;
            try {
                interval = Integer.parseInt(intervalField.getText().trim());
            } catch (NumberFormatException ex) {
                interval = 5;
            }
            if (interval < 1) interval = 1;

            autoCaptureExecutor = Executors.newSingleThreadScheduledExecutor();
            autoCaptureExecutor.scheduleAtFixedRate(
                () -> cameras.forEach(cam -> captureAndDetect(cam, false)),
                0, interval, TimeUnit.SECONDS
            );
            startBtn.setDisable(true); stopBtn.setDisable(false);
            intervalField.setDisable(true);
        });

        stopBtn.setOnAction(e -> {
            if (autoCaptureExecutor != null) {
                autoCaptureExecutor.shutdown();
                autoCaptureExecutor = null;
            }
            startBtn.setDisable(false); stopBtn.setDisable(true);
            intervalField.setDisable(false);
        });

        HBox bar = new HBox(8, intervalLabel, intervalField, startBtn, stopBtn);
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    /** 为单个摄像头创建卡片 */
    private Pane createCameraCard(CameraStream cs) {
        VBox card = new VBox(6);
        card.setStyle("-fx-background-color:white; -fx-border-color:#ddd; "
                    + "-fx-border-radius:6; -fx-padding:8;");
        card.setPrefWidth(340);

        Label nameLabel = new Label(cs.getName() + "  [" + cs.getFrameCount() + "帧]");
        nameLabel.setStyle("-fx-font-size:13; -fx-font-weight:bold;");

        ImageView view = cs.getView();
        view.setFitWidth(320);
        view.setFitHeight(240);

        Button shotBtn = new Button("📸 截图");
        shotBtn.setStyle("-fx-font-size:11;");
        shotBtn.setOnAction(e -> captureAndDetect(cs, true));

        Button removeBtn = new Button("✕");
        removeBtn.setStyle("-fx-background-color:#dc3545; -fx-text-fill:white; -fx-font-size:11;");
        removeBtn.setOnAction(e -> {
            cs.stop();
            cameras.remove(cs);
            ScheduledExecutorService ex = (ScheduledExecutorService) card.getUserData();
            if (ex != null) ex.shutdown();
            card.setVisible(false);
            card.setManaged(false);
            cameraGrid.getChildren().remove(card);
        });

        HBox btnBar = new HBox(8, shotBtn, removeBtn);
        btnBar.setAlignment(Pos.CENTER_LEFT);

        // 定时刷新帧数
        ScheduledExecutorService fpsUpdater = Executors.newSingleThreadScheduledExecutor();
        fpsUpdater.scheduleAtFixedRate(
            () -> Platform.runLater(() ->
                nameLabel.setText(cs.getName() + "  [" + cs.getFrameCount() + "帧]")),
            1, 1, TimeUnit.SECONDS
        );
        card.setUserData(fpsUpdater);

        card.getChildren().addAll(nameLabel, view, btnBar);
        return card;
    }

    /** 截图并进行 YOLO 检测 */
    private void captureAndDetect(CameraStream cs, boolean showPopup) {
        try {
            Image frame = cs.getFrame();
            if (frame == null) return;

            // 保存为临时文件
            byte[] pngBytes = javafxImageToPngBytes(frame);
            File tempFile = File.createTempFile("cam_", ".png");
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                fos.write(pngBytes);
            }

            List<YoloDetector.Detection> detections = new ArrayList<>();
            String resultPath = YoloDetector.detect(tempFile.getAbsolutePath(), detections);

            if (!detections.isEmpty() && dbManager.isConnected()) {
                saveDetectionToDb(tempFile.getAbsolutePath(), resultPath, detections);
            }

            if (showPopup) {
                Platform.runLater(() -> {
                    StringBuilder sb = new StringBuilder(cs.getName() + " 检测结果：\n");
                    if (detections.isEmpty()) {
                        sb.append("未检测到目标\n");
                    } else {
                        for (YoloDetector.Detection d : detections)
                            sb.append("· ").append(d.toString()).append("\n");
                    }
                    showScreenshotPopup(sb.toString(),
                        new Image(new File(resultPath).toURI().toString()));
                });
            } else {
                System.out.println(cs.getName() + " 自动截图: " + resultPath);
            }
        } catch (Exception ex) {
            Platform.runLater(() ->
                FX.showAlert(Alert.AlertType.ERROR, "截图失败", ex.getMessage()));
        }
    }

    /** 弹出截图结果窗口 */
    private void showScreenshotPopup(String text, Image img) {
        Stage popup = new Stage();
        popup.setTitle("YOLO 检测结果");
        popup.initModality(Modality.NONE);

        VBox content = new VBox(10);
        content.setPadding(new Insets(15));
        content.setStyle("-fx-background-color:white;");

        ImageView iv = new ImageView(img);
        iv.setPreserveRatio(true);
        iv.setFitWidth(500);

        TextArea ta = new TextArea(text);
        ta.setWrapText(true);
        ta.setEditable(false);
        ta.setPrefRowCount(6);
        ta.setStyle("-fx-font-size:13;");

        content.getChildren().addAll(iv, ta);

        Scene scene = new Scene(content);
        scene.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ESCAPE) popup.close(); });
        popup.setScene(scene);
        popup.show();
    }

    // ==================== 工具方法 ====================

    private byte[] javafxImageToPngBytes(Image img) throws Exception {
        int w = (int) img.getWidth();
        int h = (int) img.getHeight();
        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        javafx.scene.image.PixelReader pr = img.getPixelReader();
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                bi.setRGB(x, y, pr.getArgb(x, y));
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(bi, "png", baos);
        return baos.toByteArray();
    }

    private void saveDetectionToDb(String imagePath, String resultPath,
                                    List<YoloDetector.Detection> detections) {
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
            dbManager.saveDetection(imagePath, resultPath, detections.size(),
                types.toString(), json.toString());
        } catch (Exception ignored) {}
    }
}

// ==================== 摄像头视频流（同文件，供 CameraScene 和 FX 使用） ====================

/**
 * 摄像头视频流读取器
 *
 * 支持 MJPEG 流（持续读取视频帧）和 HTTP 快照（定时拉取单帧）两种模式。
 */
class CameraStream {

    private final String name;
    private final String url;
    private final javafx.scene.image.ImageView view;
    private volatile boolean running;
    private volatile boolean snapshotMode;
    private javafx.scene.image.Image currentFrame;
    private Thread thread;
    private int frameCount;

    public CameraStream(String name, String url) {
        this.name = name;
        this.url = url;
        this.view = new javafx.scene.image.ImageView();
        view.setPreserveRatio(true);
        view.setFitWidth(320);
        view.setFitHeight(240);
        view.setStyle("-fx-background-color:#111;");
    }

    public void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::readStreamLoop);
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        if (thread != null) thread.interrupt();
    }

    public javafx.scene.image.Image getFrame()        { return currentFrame; }
    public javafx.scene.image.ImageView getView()      { return view; }
    public String getName()                             { return name; }
    public boolean isRunning()                          { return running; }
    public int getFrameCount()                          { return frameCount; }
    public String getUrl()                              { return url; }

    private void readStreamLoop() {
        while (running) {
            java.net.HttpURLConnection conn = null;
            try {
                conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(8000);
                String ct = conn.getContentType();
                if (ct != null && ct.contains("multipart/x-mixed-replace")) {
                    snapshotMode = false;
                    readMjpegFrames(conn.getInputStream());
                } else {
                    snapshotMode = true;
                    byte[] data = conn.getInputStream().readAllBytes();
                    updateFrame(data);
                }
            } catch (Exception ignored) {
            } finally {
                if (conn != null) try { conn.disconnect(); } catch (Exception ignored) {}
            }
            if (snapshotMode && running) {
                try { Thread.sleep(300); } catch (InterruptedException e) { break; }
            }
        }
    }

    private void readMjpegFrames(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        int prev = 0, b;
        boolean inFrame = false;
        while (running && (b = in.read()) != -1) {
            if (!inFrame) {
                if (prev == 0xFF && b == 0xD8) {
                    inFrame = true;
                    buf.reset();
                    buf.write(0xFF); buf.write(0xD8);
                }
            } else {
                buf.write(b);
                if (prev == 0xFF && b == 0xD9) {
                    updateFrame(buf.toByteArray());
                    inFrame = false;
                }
            }
            prev = b;
        }
    }

    private void updateFrame(byte[] jpegBytes) {
        try {
            javafx.scene.image.Image img = new javafx.scene.image.Image(new java.io.ByteArrayInputStream(jpegBytes));
            if (!img.isError()) {
                currentFrame = img;
                frameCount++;
                javafx.application.Platform.runLater(() -> view.setImage(img));
            }
        } catch (Exception ignored) {}
    }
}
