

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.*;
import javafx.scene.Scene;
import javafx.scene.chart.*;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.stage.*;

import java.awt.Desktop;
import java.io.*;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.*;

/**
 * 设置与数据分析场景
 *
 * 包含：
 * - 模型设置（URL、模型名、API密钥）
 * - 输出路径配置
 * - 用户管理（仅管理员）
 * - 缺陷台账（筛选、报告生成、对比）
 * - 统计仪表盘（饼图、柱状图）
 *
 * @author 长沙理工大学
 */
public class SettingsScene {

    private final DBManager dbManager;
    private final String currentUser;
    private final String currentRole;

    private Pane root;

    public SettingsScene(DBManager dbManager, String currentUser, String currentRole) {
        this.dbManager = dbManager;
        this.currentUser = currentUser;
        this.currentRole = currentRole;
        buildUI();
    }

    public Pane getRoot() { return root; }

    private boolean isAdmin() { return "admin".equals(currentRole); }

    // ==================== UI 构建 ====================

    private void buildUI() {
        VBox pane = new VBox(20);
        pane.setPadding(new Insets(25, 40, 25, 40));
        pane.setStyle("-fx-background-color: white;");

        Label title = new Label("设置");
        title.setStyle("-fx-font-size:22; -fx-font-weight:bold;");

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(14);

        String labelStyle = "-fx-font-size:14; -fx-font-weight:bold; -fx-min-width:80;";
        String fieldStyle = "-fx-padding:8 10; -fx-font-size:13;";
        String btnBase = "-fx-font-size:13; -fx-padding:8 20; -fx-cursor:hand;";
        String primaryBtn = btnBase + "-fx-background-color:#00adb5; -fx-text-fill:white; -fx-font-weight:bold;";

        int row = 0;

        // ---- 模型设置 ----
        Label modelSection = new Label("模型设置");
        modelSection.setStyle("-fx-font-size:15; -fx-font-weight:bold; -fx-text-fill:#333;");
        grid.add(modelSection, 0, row++, 2, 1);

        TextField urlField   = addFieldRow(grid, row++, "URL",      AppConfig.url,               labelStyle, fieldStyle);
        TextField modelField = addFieldRow(grid, row++, "Model",    AppConfig.model,             labelStyle, fieldStyle);
        TextField apiField   = addFieldRow(grid, row++, "API Key",  nz(AppConfig.api),           labelStyle, fieldStyle);

        Button saveBtn = new Button("保存设置");
        saveBtn.setStyle(primaryBtn);
        Button resetBtn = new Button("恢复默认");
        resetBtn.setStyle(btnBase + "-fx-background-color:#6c757d; -fx-text-fill:white;");

        resetBtn.setOnAction(ev -> {
            urlField.setText(AppConfig.DEFAULT_URL);
            modelField.setText(AppConfig.DEFAULT_MODEL);
            apiField.setText("");
        });

        HBox modelBtnBar = new HBox(15, saveBtn, resetBtn);
        grid.add(modelBtnBar, 1, row++);

        // ---- 输出路径 ----
        grid.add(new Separator(), 0, row++, 2, 1);
        Label pathSection = new Label("输出路径");
        pathSection.setStyle("-fx-font-size:15; -fx-font-weight:bold; -fx-text-fill:#333;");
        grid.add(pathSection, 0, row++, 2, 1);

        TextField pathField = new TextField(AppConfig.outputPath);
        pathField.setStyle(fieldStyle);
        Button browseBtn = new Button("浏览");
        browseBtn.setStyle(btnBase + "-fx-background-color:#e0e0e0;");
        browseBtn.setOnAction(ev -> {
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle("选择输出目录");
            File dir = dc.showDialog(null);
            if (dir != null) pathField.setText(dir.getAbsolutePath());
        });
        Button pathOkBtn = new Button("确认路径");
        pathOkBtn.setStyle(primaryBtn);
        pathOkBtn.setOnAction(ev -> {
            AppConfig.outputPath = pathField.getText().trim();
            if (!AppConfig.outputPath.endsWith("\\") && !AppConfig.outputPath.endsWith("/")) {
                AppConfig.outputPath += "\\";
            }
            new File(AppConfig.outputPath).mkdirs();
            AppConfig.save();
            FX.showAlert(Alert.AlertType.INFORMATION, "成功", "输出路径已更新");
        });
        HBox pathBar = new HBox(8, pathField, browseBtn, pathOkBtn);
        HBox.setHgrow(pathField, Priority.ALWAYS);
        grid.add(new Label("路径") {{ setStyle(labelStyle); }}, 0, row);
        grid.add(pathBar, 1, row++);

        saveBtn.setOnAction(e -> {
            AppConfig.url   = urlField.getText().trim();
            AppConfig.model = modelField.getText().trim();
            AppConfig.api   = apiField.getText().trim();
            AppConfig.save();
            FX.showAlert(Alert.AlertType.INFORMATION, "成功", "模型设置已更新");
        });

        // ---- 关于 / 帮助 ----
        grid.add(new Separator(), 0, row++, 2, 1);
        Button aboutBtn = new Button("关于");
        aboutBtn.setStyle(btnBase + "-fx-background-color:#393e46; -fx-text-fill:white; -fx-min-width:100;");
        aboutBtn.setOnAction(e -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("关于");
            alert.setHeaderText("多功能工具箱");
            alert.setContentText("王八大王乌龟仙人@长沙理工大学 制作");
            alert.showAndWait();
        });
        Button helpBtn = new Button("帮助");
        helpBtn.setStyle(btnBase + "-fx-background-color:#393e46; -fx-text-fill:white; -fx-min-width:100;");
        helpBtn.setOnAction(e -> {
            try {
                File readme = new File("src/README.md");
                if (readme.exists()) Desktop.getDesktop().open(readme);
                else FX.showAlert(Alert.AlertType.WARNING, "提示", "未找到 README.md");
            } catch (Exception ex) { FX.showAlert(Alert.AlertType.ERROR, "错误", "无法打开帮助文件"); }
        });
        grid.add(new HBox(15, aboutBtn, helpBtn), 1, row++);

        // ---- 用户管理 ----
        grid.add(new Separator(), 0, row++, 2, 1);
        Label userSection = new Label("用户管理");
        userSection.setStyle("-fx-font-size:15; -fx-font-weight:bold; -fx-text-fill:#333;");
        grid.add(userSection, 0, row++, 2, 1);

        Button userMgmtBtn = new Button("👥 管理用户");
        userMgmtBtn.setStyle(primaryBtn);
        userMgmtBtn.setOnAction(e -> showUserManagement());
        if (!isAdmin()) userMgmtBtn.setVisible(false);
        grid.add(userMgmtBtn, 1, row++);

        ColumnConstraints col1 = new ColumnConstraints(80);
        ColumnConstraints col2 = new ColumnConstraints(450);
        grid.getColumnConstraints().addAll(col1, col2);

        VBox content = new VBox(25, title, new Separator(), grid);
        content.setMaxWidth(600);
        VBox.setVgrow(content, Priority.ALWAYS);
        pane.getChildren().add(content);
        root = pane;
    }

    private TextField addFieldRow(GridPane grid, int row, String label,
                                   String value, String labelStyle, String fieldStyle) {
        Label lbl = new Label(label);
        lbl.setStyle(labelStyle);
        TextField tf = new TextField(value);
        tf.setStyle(fieldStyle);
        grid.add(lbl, 0, row);
        grid.add(tf, 1, row);
        return tf;
    }

    private static String nz(String s) { return s == null ? "" : s; }

    // ==================== 用户管理 ====================

    private void showUserManagement() {
        if (!isAdmin()) {
            FX.showAlert(Alert.AlertType.WARNING, "权限不足", "仅管理员可管理用户");
            return;
        }
        if (!dbManager.isConnected()) {
            FX.showAlert(Alert.AlertType.WARNING, "提示", "请先连接数据库");
            return;
        }

        Stage dlg = new Stage();
        dlg.setTitle("用户管理");
        dlg.initModality(Modality.APPLICATION_MODAL);
        dlg.setWidth(500);
        dlg.setHeight(420);

        VBox pane = new VBox(12);
        pane.setPadding(new Insets(15));

        // 用户表格
        TableView<Map<String, Object>> table = new TableView<>();
        String[] cols = {"id", "username", "role"};
        String[] headers = {"ID", "用户名", "角色"};
        for (int i = 0; i < cols.length; i++) {
            TableColumn<Map<String, Object>, String> tc = new TableColumn<>(headers[i]);
            int idx = i;
            tc.setCellValueFactory(data -> {
                Object v = data.getValue().get(cols[idx]);
                return new SimpleStringProperty(v == null ? "" : v.toString());
            });
            table.getColumns().add(tc);
        }

        Runnable refreshTable = () -> {
            try {
                table.setItems(FXCollections.observableArrayList(dbManager.listUsers()));
            } catch (Exception ex) {
                FX.showAlert(Alert.AlertType.ERROR, "错误", "加载用户列表失败: " + ex.getMessage());
            }
        };
        refreshTable.run();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        VBox.setVgrow(table, Priority.ALWAYS);

        // 新增用户栏
        HBox addBar = new HBox(8);
        addBar.setAlignment(Pos.CENTER_LEFT);
        TextField newUser = new TextField(); newUser.setPromptText("用户名"); newUser.setPrefWidth(100);
        PasswordField newPwd = new PasswordField(); newPwd.setPromptText("密码"); newPwd.setPrefWidth(100);
        ComboBox<String> roleCombo = new ComboBox<>();
        roleCombo.getItems().addAll("user", "admin");
        roleCombo.setValue("user");
        roleCombo.setPrefWidth(80);

        Button addBtn = new Button("新增");
        addBtn.setStyle("-fx-background-color:#28a745; -fx-text-fill:white;");
        addBtn.setOnAction(e -> {
            String uname = newUser.getText().trim();
            String upwd = newPwd.getText().trim();
            if (uname.isEmpty() || upwd.isEmpty()) {
                FX.showAlert(Alert.AlertType.WARNING, "提示", "用户名和密码不能为空");
                return;
            }
            try {
                dbManager.addUser(uname, upwd, roleCombo.getValue());
                newUser.clear(); newPwd.clear();
                refreshTable.run();
            } catch (Exception ex) {
                FX.showAlert(Alert.AlertType.ERROR, "错误", "新增失败: " + ex.getMessage());
            }
        });
        addBar.getChildren().addAll(new Label("新增:"), newUser, newPwd, roleCombo, addBtn);

        // 操作按钮
        Button delBtn = new Button("删除选中");
        delBtn.setStyle("-fx-background-color:#dc3545; -fx-text-fill:white;");
        delBtn.setOnAction(e -> {
            Map<String, Object> sel = table.getSelectionModel().getSelectedItem();
            if (sel == null) { FX.showAlert(Alert.AlertType.WARNING, "提示", "请选中一个用户"); return; }
            String uname = sel.get("username").toString();
            if ("admin".equals(uname)) { FX.showAlert(Alert.AlertType.WARNING, "提示", "不能删除 admin 账户"); return; }
            try { dbManager.deleteUser(uname); refreshTable.run(); }
            catch (Exception ex) { FX.showAlert(Alert.AlertType.ERROR, "错误", "删除失败: " + ex.getMessage()); }
        });

        Button resetPwdBtn = new Button("重置密码");
        resetPwdBtn.setStyle("-fx-background-color:#ffc107; -fx-text-fill:#333;");
        resetPwdBtn.setOnAction(e -> {
            Map<String, Object> sel = table.getSelectionModel().getSelectedItem();
            if (sel == null) { FX.showAlert(Alert.AlertType.WARNING, "提示", "请选中一个用户"); return; }
            String uname = sel.get("username").toString();
            TextInputDialog tid = new TextInputDialog();
            tid.setTitle("重置密码"); tid.setHeaderText("为 " + uname + " 设置新密码");
            tid.showAndWait().ifPresent(np -> {
                if (!np.trim().isEmpty()) {
                    try { dbManager.resetPassword(uname, np.trim());
                        FX.showAlert(Alert.AlertType.INFORMATION, "成功", "密码已重置"); }
                    catch (Exception ex) { FX.showAlert(Alert.AlertType.ERROR, "错误", ex.getMessage()); }
                }
            });
        });

        HBox btnBar = new HBox(10, delBtn, resetPwdBtn);
        pane.getChildren().addAll(new Label("用户列表"), table, addBar, btnBar);
        dlg.setScene(new Scene(pane));
        dlg.show();
    }

    // ==================== 缺陷台账（公开静态方法，DbScene 也调用） ====================

    static void showDefectLedger(DBManager dbManager) {
        if (!dbManager.isConnected()) {
            FX.showAlert(Alert.AlertType.WARNING, "提示", "请先连接数据库");
            return;
        }
        try {
            dbManager.ensureDefectTable();
            List<Map<String, Object>> allRows = dbManager.getDetections();
            if (allRows.isEmpty()) {
                FX.showAlert(Alert.AlertType.INFORMATION, "提示", "暂无缺陷记录");
                return;
            }

            Stage dlg = new Stage();
            dlg.setTitle("缺陷台账");
            dlg.initModality(Modality.NONE);
            dlg.setWidth(1000);
            dlg.setHeight(650);

            // 筛选栏
            HBox filterBar = new HBox(10);
            filterBar.setAlignment(Pos.CENTER_LEFT);
            filterBar.setStyle("-fx-padding:5 0;");

            DatePicker dateStart = new DatePicker(); dateStart.setPromptText("开始日期"); dateStart.setPrefWidth(130);
            DatePicker dateEnd   = new DatePicker(); dateEnd.setPromptText("结束日期");   dateEnd.setPrefWidth(130);

            ComboBox<String> typeCombo = new ComboBox<>();
            typeCombo.setPromptText("全部类型"); typeCombo.setPrefWidth(140);
            Set<String> allTypes = new LinkedHashSet<>();
            for (Map<String, Object> row : allRows) {
                String json = row.get("detail_json") == null ? "[]" : row.get("detail_json").toString();
                for (YoloDetector.Detection d : parseDetectionJson(json)) allTypes.add(d.className);
            }
            typeCombo.getItems().add("全部类型");
            typeCombo.getItems().addAll(allTypes);
            typeCombo.setValue("全部类型");

            TextField keywordField = new TextField(); keywordField.setPromptText("关键词搜索..."); keywordField.setPrefWidth(160);
            Button resetBtn = new Button("重置"); resetBtn.setStyle("-fx-background-color:#6c757d; -fx-text-fill:white;");
            Label countLabel = new Label("共 " + allRows.size() + " 条");

            filterBar.getChildren().addAll(
                new Label("日期:"), dateStart, new Label("至"), dateEnd,
                new Label("类型:"), typeCombo, keywordField, resetBtn, countLabel
            );

            // 表格
            TableView<Map<String, Object>> table = new TableView<>();
            table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
            String[] cols = {"id", "defect_count", "defect_types", "created_at", "image_path", "result_path", "ai_analysis"};
            String[] headers = {"ID", "缺陷数", "缺陷类型", "检测时间", "原图路径", "结果路径", "AI分析"};
            for (int i = 0; i < cols.length; i++) {
                TableColumn<Map<String, Object>, String> tc = new TableColumn<>(headers[i]);
                int idx = i;
                tc.setCellValueFactory(data -> {
                    Object v = data.getValue().get(cols[idx]);
                    return new SimpleStringProperty(v == null ? "" : v.toString());
                });
                table.getColumns().add(tc);
            }
            table.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);

            // 筛选逻辑
            Runnable applyFilter = () -> {
                List<Map<String, Object>> filtered = new ArrayList<>();
                String keyword = keywordField.getText().trim().toLowerCase();
                String selType = typeCombo.getValue();
                LocalDate ds = dateStart.getValue();
                LocalDate de = dateEnd.getValue();

                for (Map<String, Object> row : allRows) {
                    if (ds != null || de != null) {
                        Object created = row.get("created_at");
                        if (created != null) {
                            try {
                                LocalDate rowDate = LocalDate.parse(created.toString().substring(0, 10));
                                if (ds != null && rowDate.isBefore(ds)) continue;
                                if (de != null && rowDate.isAfter(de)) continue;
                            } catch (Exception ignored) {}
                        }
                    }
                    if (selType != null && !selType.equals("全部类型")) {
                        String types = row.get("defect_types") == null ? "" : row.get("defect_types").toString();
                        if (!Arrays.asList(types.split(",")).contains(selType)) continue;
                    }
                    if (!keyword.isEmpty()) {
                        String types = nz(row.get("defect_types")).toLowerCase();
                        String imgPath = nz(row.get("image_path")).toLowerCase();
                        if (!types.contains(keyword) && !imgPath.contains(keyword)) continue;
                    }
                    filtered.add(row);
                }
                table.setItems(FXCollections.observableArrayList(filtered));
                countLabel.setText("共 " + filtered.size() + " 条");
            };

            table.setItems(FXCollections.observableArrayList(allRows));
            dateStart.setOnAction(e -> applyFilter.run());
            dateEnd.setOnAction(e -> applyFilter.run());
            typeCombo.setOnAction(e -> applyFilter.run());
            keywordField.textProperty().addListener((obs, old, val) -> applyFilter.run());
            resetBtn.setOnAction(e -> {
                dateStart.setValue(null); dateEnd.setValue(null);
                typeCombo.setValue("全部类型"); keywordField.clear();
                table.setItems(FXCollections.observableArrayList(allRows));
                countLabel.setText("共 " + allRows.size() + " 条");
            });

            // 按钮栏
            Button reportBtn = new Button("生成报告");
            reportBtn.setStyle("-fx-background-color:#00adb5; -fx-text-fill:white;");
            reportBtn.setOnAction(e -> {
                Map<String, Object> sel = table.getSelectionModel().getSelectedItem();
                if (sel == null) { FX.showAlert(Alert.AlertType.WARNING, "提示", "请选中一条记录"); return; }
                try {
                    String json = nz(sel.get("detail_json"));
                    String imgPath = nz(sel.get("image_path"));
                    String resPath = nz(sel.get("result_path"));
                    String aiAnalysis = nz(sel.get("ai_analysis"));
                    List<YoloDetector.Detection> dets = parseDetectionJson(json);
                    String path = generateReport(imgPath, resPath, dets, aiAnalysis);
                    try { Desktop.getDesktop().open(new File(path)); } catch (Exception ignored) {}
                } catch (Exception ex) { FX.showAlert(Alert.AlertType.ERROR, "错误", "生成报告失败: " + ex.getMessage()); }
            });

            Button compareBtn = new Button("对比选中");
            compareBtn.setStyle("-fx-background-color:#393e46; -fx-text-fill:white;");
            compareBtn.setOnAction(e -> {
                var selected = table.getSelectionModel().getSelectedItems();
                if (selected.size() != 2) {
                    FX.showAlert(Alert.AlertType.WARNING, "提示", "请用Ctrl选择两条记录进行对比");
                    return;
                }
                showComparison(selected.get(0), selected.get(1));
            });

            Button dashBtn = new Button("📊 统计仪表盘");
            dashBtn.setStyle("-fx-background-color:#4a90d9; -fx-text-fill:white;");
            dashBtn.setOnAction(e -> showDefectDashboard(allRows));

            HBox btnBar = new HBox(10, reportBtn, compareBtn, dashBtn);

            VBox rootPane = new VBox(10, filterBar, table, btnBar);
            rootPane.setPadding(new Insets(15));
            VBox.setVgrow(table, Priority.ALWAYS);

            dlg.setScene(new Scene(rootPane));
            dlg.show();
        } catch (Exception e) {
            FX.showAlert(Alert.AlertType.ERROR, "错误", e.getMessage());
        }
    }

    // ==================== 统计仪表盘 ====================

    private static void showDefectDashboard(List<Map<String, Object>> allRows) {
        Stage dlg = new Stage();
        dlg.setTitle("缺陷统计仪表盘");
        dlg.initModality(Modality.NONE);
        dlg.setWidth(1100);
        dlg.setHeight(800);

        VBox rootPane = new VBox(15);
        rootPane.setPadding(new Insets(20));
        rootPane.setStyle("-fx-background-color:#f5f7fa;");

        // 汇总数据
        int totalRecords = allRows.size();
        int totalDefects = 0;
        Map<String, Integer> typeCountMap = new LinkedHashMap<>();
        Map<String, Integer> dailyCount = new LinkedHashMap<>();

        for (Map<String, Object> row : allRows) {
            Object cnt = row.get("defect_count");
            int n = 0;
            if (cnt != null) {
                try { n = Integer.parseInt(cnt.toString()); } catch (NumberFormatException ignored) {}
            }
            totalDefects += n;

            String json = row.get("detail_json") == null ? "[]" : row.get("detail_json").toString();
            for (YoloDetector.Detection d : parseDetectionJson(json)) {
                typeCountMap.merge(d.className, 1, Integer::sum);
            }

            Object ts = row.get("created_at");
            if (ts != null) {
                dailyCount.merge(ts.toString().substring(0, 10), 1, Integer::sum);
            }
        }

        String mostType = typeCountMap.isEmpty() ? "—"
            : typeCountMap.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey();

        // 汇总卡片
        HBox cardBar = new HBox(15);
        cardBar.setAlignment(Pos.CENTER);
        cardBar.getChildren().addAll(
            makeStatCard("📋 检测次数", String.valueOf(totalRecords), "#4a90d9"),
            makeStatCard("⚠ 缺陷总数", String.valueOf(totalDefects), "#e67e22"),
            makeStatCard("🏷 最常见", mostType, "#00adb5"),
            makeStatCard("📅 日均检测", totalRecords > 0 && !dailyCount.isEmpty()
                ? String.format("%.1f", (double) totalRecords / dailyCount.size()) : "—", "#6c757d")
        );

        // 饼图
        ObservableList<PieChart.Data> pieData = FXCollections.observableArrayList();
        for (Map.Entry<String, Integer> e : typeCountMap.entrySet()) {
            pieData.add(new PieChart.Data(e.getKey() + " (" + e.getValue() + ")", e.getValue()));
        }
        PieChart pieChart = new PieChart(pieData);
        pieChart.setTitle("缺陷类型分布");
        pieChart.setLegendSide(Side.RIGHT);
        pieChart.setLabelsVisible(true);
        pieChart.setPrefSize(900, 360);

        // 柱状图
        CategoryAxis xAxis = new CategoryAxis();
        xAxis.setLabel("日期");
        NumberAxis yAxis = new NumberAxis();
        yAxis.setLabel("检测次数");
        BarChart<String, Number> barChart = new BarChart<>(xAxis, yAxis);
        barChart.setTitle("每日检测趋势");
        barChart.setPrefHeight(260);
        barChart.setPrefWidth(900);
        barChart.setLegendVisible(false);

        List<String> sortedDays = new ArrayList<>(dailyCount.keySet());
        Collections.sort(sortedDays);
        XYChart.Series<String, Number> series = new XYChart.Series<>();
        for (String day : sortedDays) {
            series.getData().add(new XYChart.Data<>(day, dailyCount.get(day)));
        }
        barChart.getData().add(series);
        barChart.setAnimated(false);

        VBox chartArea = new VBox(15, pieChart, barChart);
        chartArea.setAlignment(Pos.CENTER);
        VBox.setVgrow(chartArea, Priority.ALWAYS);

        rootPane.getChildren().addAll(cardBar, new Separator(), chartArea);
        dlg.setScene(new Scene(rootPane));
        dlg.show();
    }

    private static VBox makeStatCard(String title, String value, String color) {
        VBox card = new VBox(6);
        card.setAlignment(Pos.CENTER);
        card.setPadding(new Insets(14, 20, 14, 20));
        card.setStyle("-fx-background-color:white; -fx-background-radius:10; "
            + "-fx-border-color:" + color + "; -fx-border-width:0 0 3 0; "
            + "-fx-effect:dropshadow(gaussian, rgba(0,0,0,0.08), 6, 0, 0, 2);");
        card.setMinWidth(180);

        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-font-size:12; -fx-text-fill:#888;");

        Label valueLabel = new Label(value);
        valueLabel.setStyle("-fx-font-size:28; -fx-font-weight:bold; -fx-text-fill:" + color + ";");

        card.getChildren().addAll(titleLabel, valueLabel);
        return card;
    }

    // ==================== 历史对比 ====================

    private static void showComparison(Map<String, Object> a, Map<String, Object> b) {
        Stage dlg = new Stage();
        dlg.setTitle("缺陷历史对比");
        dlg.initModality(Modality.NONE);
        dlg.setWidth(1200);
        dlg.setHeight(700);

        HBox rootPane = new HBox(12);
        rootPane.setPadding(new Insets(15));

        for (Map<String, Object> entry : new Map[]{a, b}) {
            VBox panel = new VBox(6);
            panel.setStyle("-fx-border-color:#ddd; -fx-padding:10; -fx-background-color:#fafafa;");
            panel.setPrefWidth(520);

            String info = "#" + entry.get("id") + " | " + entry.get("created_at")
                + " | " + entry.get("defect_count") + "处缺陷";
            Label infoLabel = new Label(info);
            infoLabel.setStyle("-fx-font-size:13; -fx-font-weight:bold; -fx-text-fill:#00adb5;");
            infoLabel.setWrapText(true);

            String resPath = entry.get("result_path") == null ? "" : entry.get("result_path").toString();
            Image img = resPath.isEmpty() ? null : new Image(new File(resPath).toURI().toString());
            ImageView iv = new ImageView(img);
            iv.setPreserveRatio(true); iv.setFitWidth(480); iv.setFitHeight(280);
            iv.setStyle("-fx-cursor:hand; -fx-border-color:#ccc;");

            String json = entry.get("detail_json") == null ? "[]" : entry.get("detail_json").toString();
            List<YoloDetector.Detection> dets = parseDetectionJson(json);
            StringBuilder detailText = new StringBuilder();
            for (int i = 0; i < dets.size(); i++) {
                YoloDetector.Detection d = dets.get(i);
                detailText.append(String.format("  %d. %-12s  %.0f%%  [%d,%d → %d,%d]\n",
                    i + 1, d.className, d.confidence * 100,
                    (int) d.x1, (int) d.y1, (int) d.x2, (int) d.y2));
            }
            TextArea detailTA = new TextArea("缺陷详情:\n" + detailText);
            detailTA.setEditable(false); detailTA.setWrapText(true);
            detailTA.setPrefRowCount(Math.min(dets.size() + 1, 6));
            detailTA.setStyle("-fx-font-size:12; -fx-font-family:'Consolas','Microsoft YaHei';");

            panel.getChildren().addAll(infoLabel, iv, detailTA);
            HBox.setHgrow(panel, Priority.ALWAYS);
            rootPane.getChildren().add(panel);
        }

        ScrollPane sp = new ScrollPane(rootPane);
        sp.setFitToWidth(true);
        dlg.setScene(new Scene(sp));
        dlg.show();
    }

    // ==================== 报告生成 ====================

    static String generateReport(String imagePath, String resultPath,
                                  List<YoloDetector.Detection> detections,
                                  String aiAnalysis) {
        String reportDir = System.getProperty("user.dir") + "/src/aioutput/report";
        new File(reportDir).mkdirs();
        String baseName = "report_" + System.currentTimeMillis();
        File reportFile = new File(reportDir, baseName + ".html");

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset='UTF-8'>");
        html.append("<title>缺陷检测报告</title>");
        html.append("<style>body{font-family:'Microsoft YaHei',Arial;margin:40px;color:#333}");
        html.append("h1{color:#00adb5;border-bottom:2px solid #00adb5;padding-bottom:8px}");
        html.append("table{border-collapse:collapse;width:100%;margin:15px 0}");
        html.append("th{background:#00adb5;color:white;padding:10px 12px;text-align:left}");
        html.append("td{padding:8px 12px;border-bottom:1px solid #ddd}");
        html.append(".img-box{text-align:center;margin:20px 0}");
        html.append(".img-box img{max-width:100%;border:2px solid #ddd;border-radius:8px}");
        html.append("</style></head><body>");
        html.append("<h1>土木结构缺陷检测报告</h1>");
        html.append("<p style='color:#888'>生成时间: ")
            .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())).append("</p>");
        html.append("<h2>检测结果 (").append(detections.size()).append(" 处缺陷)</h2>");

        if (!detections.isEmpty()) {
            html.append("<table><tr><th>编号</th><th>类型</th><th>置信度</th><th>边界框</th></tr>");
            for (int i = 0; i < detections.size(); i++) {
                YoloDetector.Detection d = detections.get(i);
                html.append("<tr><td>").append(i + 1).append("</td>");
                html.append("<td><strong>").append(d.className).append("</strong></td>");
                html.append("<td>").append(String.format("%.1f%%", d.confidence * 100)).append("</td>");
                html.append("<td>(").append((int)d.x1).append(",").append((int)d.y1)
                    .append(",").append((int)d.x2).append(",").append((int)d.y2).append(")</td></tr>");
            }
            html.append("</table>");
        }

        if (aiAnalysis != null && !aiAnalysis.isEmpty()) {
            html.append("<h2>AI 分析报告</h2>");
            html.append("<div style='background:#f9f9f9;border-left:4px solid #00adb5;")
                .append("padding:15px 20px;margin:15px 0;border-radius:4px;'>");
            html.append("<pre style='white-space:pre-wrap;font-family:inherit;")
                .append("font-size:15px;line-height:1.7;margin:0;'>");
            html.append(aiAnalysis
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;"));
            html.append("</pre></div>");
        }

        html.append("<div class='img-box'><img src='file:///")
            .append(resultPath.replace("\\", "/")).append("'/></div>");
        html.append("<p style='text-align:center;color:#888'>土木智能运维系统 · 自动生成报告</p>");
        html.append("</body></html>");

        try (FileWriter fw = new FileWriter(reportFile)) { fw.write(html.toString()); }
        catch (IOException ignored) {}

        // 同时生成 txt 纯文本版
        File txtFile = new File(reportDir, baseName + ".txt");
        try (FileWriter fw = new FileWriter(txtFile)) {
            fw.write("土木结构缺陷检测报告\n");
            fw.write("生成时间: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + "\n\n");
            fw.write("检测结果 (" + detections.size() + " 处缺陷):\n");
            for (YoloDetector.Detection d : detections) fw.write("  " + d.toString() + "\n");
        } catch (IOException ignored) {}

        return reportFile.getAbsolutePath();
    }

    // ==================== JSON 解析工具 ====================

    private static List<YoloDetector.Detection> parseDetectionJson(String json) {
        List<YoloDetector.Detection> list = new ArrayList<>();
        int depth = 0, start = -1;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') { if (depth == 0) start = i; depth++; }
            else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    String obj = json.substring(start, i + 1);
                    String cls = extractJsonStr(obj, "class");
                    double conf = extractJsonDouble(obj, "conf");
                    int boxStart = obj.indexOf("\"box\":[") + 7;
                    int boxEnd = obj.indexOf("]", boxStart);
                    String[] parts = obj.substring(boxStart, boxEnd).split(",");
                    double x1 = Double.parseDouble(parts[0].trim());
                    double y1 = Double.parseDouble(parts[1].trim());
                    double x2 = Double.parseDouble(parts[2].trim());
                    double y2 = Double.parseDouble(parts[3].trim());
                    list.add(new YoloDetector.Detection(x1, y1, x2, y2, conf, cls));
                    start = -1;
                }
            }
        }
        return list;
    }

    private static String extractJsonStr(String obj, String key) {
        String search = "\"" + key + "\":\"";
        int i = obj.indexOf(search);
        if (i < 0) return "";
        i += search.length();
        int end = obj.indexOf("\"", i);
        return end < 0 ? obj.substring(i) : obj.substring(i, end);
    }

    private static double extractJsonDouble(String obj, String key) {
        String search = "\"" + key + "\":";
        int i = obj.indexOf(search);
        if (i < 0) return 0;
        i += search.length();
        int end = i;
        while (end < obj.length() && (Character.isDigit(obj.charAt(end)) || obj.charAt(end) == '.')) end++;
        return Double.parseDouble(obj.substring(i, end));
    }

    private static String nz(Object o) { return o == null ? "" : o.toString(); }
}
