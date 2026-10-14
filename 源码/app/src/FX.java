

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.*;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.Text;
import javafx.stage.*;

import java.io.*;
import java.util.*;

/**
 * 土木智能运维系统 — JavaFX 版主程序
 *
 * 左侧菜单栏切换四个功能场景：
 * 1. 对话 — AI 聊天 + 图片分析 + 数据库检索
 * 2. 数据库 — 数据库可视化浏览与编辑
 * 3. 摄像头 — 摄像头管理与实时 YOLO 检测
 * 4. 设置 — 模型配置 + 用户管理 + 缺陷台账
 *
 * @author 长沙理工大学
 */
public class FX extends Application {

    // ==================== 共享状态（同包场景类可访问） ====================

    DBManager dbManager = new DBManager();
    ChatHistory chatHistory = new ChatHistory();
    List<CameraStream> cameras = new ArrayList<>();
    String currentUser;
    String currentRole;

    // ==================== UI 组件 ====================

    private StackPane contentArea;
    private final Map<String, Pane> scenes = new HashMap<>();
    private Button chatBtn, dbBtn, camBtn, setBtn;

    private ChatScene chatScene;
    private DbScene dbScene;
    private CameraScene cameraScene;

    // ==================== 程序入口 ====================

    @Override
    public void start(Stage primaryStage) {
        // 加载配置
        AppConfig.load();

        // 尝试连接数据库
        boolean dbOk = false;
        try {
            dbManager.connectEmbedded();
            dbManager.ensureDefectTable();
            dbManager.ensureUserTable();
            dbOk = true;
            System.out.println("数据库已连接");
        } catch (Exception e) {
            System.out.println("数据库未连接，离线模式");
        }

        // YOLO 模型预热（后台线程，不阻塞启动）
        new Thread(() -> {
            try {
                // 用自身 yolo_infer.py 目录下的任意图片做预热，没有就跳过
                String warmImage = System.getProperty("user.dir") + "/best.pt";
                File testImg = new File(System.getProperty("user.dir") + "/乌龟.png");
                if (testImg.exists()) warmImage = testImg.getAbsolutePath();
                else { System.out.println("YOLO 预热跳过（无测试图片）"); return; }

                System.out.println("YOLO 预热中...");
                long start = System.currentTimeMillis();
                YoloDetector.detect(warmImage);
                long sec = (System.currentTimeMillis() - start) / 1000;
                System.out.println("YOLO 预热完成，耗时 " + sec + " 秒");
            } catch (Throwable e) {
                System.out.println("YOLO 预热跳过: " + e.getMessage());
            }
        }).start();

        // 登录验证
        if (dbOk && !showLoginDialog()) {
            Platform.exit();
            return;
        }

        // 构建主界面
        primaryStage.setTitle("多功能工具箱 - JavaFX");
        primaryStage.setWidth(1100);
        primaryStage.setHeight(700);

        BorderPane root = new BorderPane();
        root.setLeft(createLeftMenu());

        contentArea = new StackPane();
        contentArea.setStyle("-fx-background-color: #f6f8fa;");
        root.setCenter(contentArea);

        initScenes();
        switchScene("chat");

        // 关闭确认
        primaryStage.setOnCloseRequest(event -> {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("确认");
            alert.setHeaderText("确定关闭吗？");
            Optional<ButtonType> result = alert.showAndWait();
            if (result.isEmpty() || result.get() != ButtonType.OK) {
                event.consume();
            }
        });

        primaryStage.setScene(new Scene(root));
        primaryStage.show();

        // 同步数据库 UI 状态
        if (dbOk) {
            Platform.runLater(() -> {
                if (dbScene != null) {
                    dbScene.setConnected(true);
                    dbScene.refreshTableList();
                }
            });
        }
    }

    // ==================== 左侧菜单 ====================

    private VBox createLeftMenu() {
        VBox box = new VBox(12);
        box.setPadding(new Insets(20, 15, 20, 15));
        box.setPrefWidth(180);
        box.setStyle("-fx-background-color: #222831;");

        Text title = new Text("功能菜单");
        title.setStyle("-fx-font-size: 16px; -fx-fill: white; -fx-font-weight: bold;");

        chatBtn = createMenuBtn("💬 对话",   () -> switchScene("chat"));
        dbBtn   = createMenuBtn("🗄 数据库", () -> switchScene("db"));
        camBtn  = createMenuBtn("📷 摄像头", () -> switchScene("camera"));
        setBtn  = createMenuBtn("⚙ 设置",   () -> switchScene("settings"));

        VBox spacer = new VBox();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        box.getChildren().addAll(title, new Separator(), chatBtn, dbBtn, camBtn, setBtn, spacer);
        return box;
    }

    private Button createMenuBtn(String text, Runnable action) {
        Button btn = new Button(text);
        btn.setPrefWidth(150);
        btn.setStyle("-fx-background-color: #393e46; -fx-text-fill: white; "
                   + "-fx-padding:10; -fx-font-size:14;");
        btn.setOnAction(e -> action.run());
        return btn;
    }

    // ==================== 场景管理 ====================

    private void initScenes() {
        chatScene = new ChatScene(dbManager, chatHistory, currentRole);
        dbScene = new DbScene(dbManager, currentRole);
        cameraScene = new CameraScene(dbManager, cameras);
        SettingsScene settingsScene = new SettingsScene(dbManager, currentUser, currentRole);

        scenes.put("chat",     chatScene.getRoot());
        scenes.put("db",       dbScene.getRoot());
        scenes.put("camera",   cameraScene.getRoot());
        scenes.put("settings", settingsScene.getRoot());
    }

    private void switchScene(String key) {
        contentArea.getChildren().clear();
        contentArea.getChildren().add(scenes.get(key));

        String normal = "-fx-background-color: #393e46; -fx-text-fill: white; "
                      + "-fx-padding:10; -fx-font-size:14;";
        String active = "-fx-background-color: #00adb5; -fx-text-fill: white; "
                      + "-fx-padding:10; -fx-font-size:14;";

        chatBtn.setStyle(normal);
        dbBtn.setStyle(normal);
        camBtn.setStyle(normal);
        setBtn.setStyle(normal);

        switch (key) {
            case "chat"     -> chatBtn.setStyle(active);
            case "db"       -> dbBtn.setStyle(active);
            case "camera"   -> camBtn.setStyle(active);
            case "settings" -> setBtn.setStyle(active);
        }
    }

    // ==================== 登录对话框 ====================

    private boolean showLoginDialog() {
        Stage loginStage = new Stage();
        loginStage.setTitle("登录 - 土木智能运维系统");
        loginStage.initModality(Modality.APPLICATION_MODAL);
        loginStage.setResizable(false);

        VBox body = new VBox(15);
        body.setPadding(new Insets(30));
        body.setAlignment(Pos.CENTER);
        body.setStyle("-fx-background-color:white;");

        Label title = new Label("土木智能运维系统");
        title.setStyle("-fx-font-size:22; -fx-font-weight:bold; -fx-text-fill:#00adb5;");

        TextField userField = new TextField();
        userField.setPromptText("用户名");
        userField.setPrefWidth(250);
        userField.setStyle("-fx-padding:10; -fx-font-size:14;");

        PasswordField pwdField = new PasswordField();
        pwdField.setPromptText("密码");
        pwdField.setPrefWidth(250);
        pwdField.setStyle("-fx-padding:10; -fx-font-size:14;");

        Label errorLabel = new Label();
        errorLabel.setStyle("-fx-text-fill:#dc3545; -fx-font-size:12;");

        Label hintLabel = new Label("默认账号: admin / admin");
        hintLabel.setStyle("-fx-text-fill:#aaa; -fx-font-size:11;");

        final boolean[] loggedIn = {false};

        Button loginBtn = new Button("登 录");
        loginBtn.setPrefWidth(250);
        loginBtn.setStyle("-fx-background-color:#00adb5; -fx-text-fill:white; "
                        + "-fx-font-size:14; -fx-padding:10;");

        Runnable doLogin = () -> {
            String user = userField.getText().trim();
            String pwd = pwdField.getText().trim();
            if (user.isEmpty() || pwd.isEmpty()) {
                errorLabel.setText("请输入用户名和密码");
                return;
            }
            try {
                if (dbManager.verifyLogin(user, pwd)) {
                    loggedIn[0] = true;
                    currentUser = user;
                    currentRole = dbManager.getUserRole(user);
                    loginStage.close();
                } else {
                    errorLabel.setText("用户名或密码错误");
                }
            } catch (Exception ex) {
                errorLabel.setText("数据库验证失败: " + ex.getMessage());
            }
        };

        loginBtn.setOnAction(e -> doLogin.run());
        pwdField.setOnAction(e -> doLogin.run());
        loginStage.setOnCloseRequest(e -> { if (!loggedIn[0]) Platform.exit(); });

        body.getChildren().addAll(title, userField, pwdField, errorLabel, loginBtn, hintLabel);

        loginStage.setScene(new Scene(body, 350, 320));
        loginStage.showAndWait();
        return loggedIn[0];
    }

    // ==================== 应用关闭 ====================

    @Override
    public void stop() {
        for (CameraStream cs : cameras) cs.stop();
        if (cameraScene != null) cameraScene.shutdown();
    }

    // ==================== 公共工具方法（同包场景类可调用） ====================

    /** 格式化 SQL 值（字符串加引号，数字不加） */
    static String formatSqlValue(Object val) {
        if (val == null) return "NULL";
        if (val instanceof Number) return val.toString();
        return "'" + val.toString().replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    /** 弹出提示对话框 */
    static void showAlert(Alert.AlertType type, String title, String msg) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(null);
        if (msg != null && msg.length() > 80) {
            TextArea ta = new TextArea(msg);
            ta.setWrapText(true);
            ta.setEditable(false);
            ta.setPrefRowCount(Math.min(8, (int) msg.lines().count() + 1));
            ta.setMaxWidth(500);
            alert.getDialogPane().setContent(ta);
        } else {
            alert.setContentText(msg);
        }
        alert.showAndWait();
    }

    // ==================== 主入口 ====================

    public static void main(String[] args) {
        launch(args);
    }
}
