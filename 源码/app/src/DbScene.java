

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.*;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.stage.*;

import java.util.*;

/**
 * 数据库可视化管理场景
 *
 * 提供数据库连接、表浏览、SQL 控制台、数据增删改查功能。
 * 支持双击单元格直接编辑，自动同步到数据库。
 *
 * @author 长沙理工大学
 */
public class DbScene {

    private final DBManager dbManager;
    private final boolean isAdmin;

    // UI 组件
    private Pane root;
    private TableView<Map<String, Object>> dataTable;
    private ListView<String> tableList;
    private Label dbStatus;
    private Button connectBtn, disconnectBtn;
    private String currentTable;

    public DbScene(DBManager dbManager, String currentRole) {
        this.dbManager = dbManager;
        this.isAdmin = "admin".equals(currentRole);
        buildUI();
    }

    public Pane getRoot() { return root; }

    // ==================== UI 构建 ====================

    private void buildUI() {
        VBox pane = new VBox(10);
        pane.setPadding(new Insets(15));
        pane.setStyle("-fx-background-color: white;");

        Label title = new Label("数据库可视化管理");
        title.setStyle("-fx-font-size:20; -fx-font-weight:bold;");

        // 连接栏
        HBox connBar = buildConnectionBar();

        // 主内容区：左表列表 + 右数据表格
        dataTable = new TableView<>();
        dataTable.setEditable(true);
        dataTable.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);

        tableList = new ListView<>();
        tableList.setPrefWidth(150);
        tableList.setOnMouseClicked(ev -> {
            String selected = tableList.getSelectionModel().getSelectedItem();
            if (selected != null && !selected.equals(currentTable)) {
                currentTable = selected;
                loadTableData(currentTable);
            }
        });

        VBox leftPane = new VBox(6, new Label("表列表"), tableList);
        leftPane.setPrefWidth(160);
        VBox.setVgrow(tableList, Priority.ALWAYS);

        SplitPane split = new SplitPane(leftPane, dataTable);
        split.setDividerPositions(0.2);
        VBox.setVgrow(split, Priority.ALWAYS);

        // SQL 控制台
        HBox sqlBar = buildSqlBar();

        // 操作栏
        HBox actionBar = buildActionBar();

        pane.getChildren().addAll(title, new Separator(), connBar, new Separator(),
            split, new Separator(), sqlBar, actionBar);
        root = pane;
    }

    // ---- 连接栏 ----
    private HBox buildConnectionBar() {
        HBox bar = new HBox(8);
        bar.setAlignment(Pos.CENTER_LEFT);

        TextField dbName = new TextField("bridge");    dbName.setPrefWidth(80);
        TextField user   = new TextField("sa");        user.setPrefWidth(60);
        PasswordField pwd = new PasswordField();        pwd.setPrefWidth(60);
        pwd.setText("");

        Label hint = new Label("H2 文件数据库 · 启动即自动连接");
        hint.setStyle("-fx-text-fill:#888; -fx-font-size:11;");

        connectBtn = new Button("连接");
        connectBtn.setStyle("-fx-background-color:#28a745; -fx-text-fill:white;");
        disconnectBtn = new Button("断开");
        disconnectBtn.setStyle("-fx-background-color:#dc3545; -fx-text-fill:white;");
        disconnectBtn.setDisable(true);

        dbStatus = new Label("○ 未连接");
        dbStatus.setStyle("-fx-text-fill:#999; -fx-font-size:13;");

        connectBtn.setOnAction(e -> {
            try {
                dbManager.connect("", "", dbName.getText(),
                    user.getText(), pwd.getText());
                dbManager.ensureDefectTable();
                setConnected(true);
                refreshTableList();
            } catch (Exception ex) {
                FX.showAlert(Alert.AlertType.ERROR, "连接失败", ex.getMessage());
            }
        });

        disconnectBtn.setOnAction(e -> {
            dbManager.disconnect();
            setConnected(false);
            tableList.getItems().clear();
            dataTable.getColumns().clear();
            dataTable.getItems().clear();
        });

        bar.getChildren().addAll(
            new Label("数据库"), dbName,
            new Label("用户"), user,
            new Label("密码"), pwd,
            hint, connectBtn, disconnectBtn, dbStatus
        );
        return bar;
    }

    // ---- SQL 栏 ----
    private HBox buildSqlBar() {
        TextField sqlField = new TextField();
        sqlField.setPromptText("输入 SQL 语句，SELECT 显示结果，其他显示影响行数...");
        HBox.setHgrow(sqlField, Priority.ALWAYS);

        Button execBtn = new Button("执行");
        execBtn.setStyle("-fx-background-color:#00adb5; -fx-text-fill:white;");

        execBtn.setOnAction(e -> {
            if (!dbManager.isConnected()) {
                FX.showAlert(Alert.AlertType.WARNING, "提示", "请先连接数据库");
                return;
            }
            try {
                String sql = sqlField.getText().trim();
                if (sql.isEmpty()) return;
                String lower = sql.toLowerCase();
                if (lower.startsWith("select") || lower.startsWith("show")
                    || lower.startsWith("describe")) {
                    List<Map<String, Object>> rows = dbManager.query(sql);
                    buildDynamicColumns(rows);
                    dataTable.setItems(FXCollections.observableArrayList(rows));
                } else {
                    int affected = dbManager.execute(sql);
                    FX.showAlert(Alert.AlertType.INFORMATION, "执行成功", "影响行数: " + affected);
                    if (currentTable != null) loadTableData(currentTable);
                }
            } catch (Exception ex) {
                FX.showAlert(Alert.AlertType.ERROR, "SQL 错误", ex.getMessage());
            }
        });

        HBox bar = new HBox(8, sqlField, execBtn);
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    // ---- 操作栏 ----
    private HBox buildActionBar() {
        Button refreshBtn = new Button("刷新");
        refreshBtn.setOnAction(e -> {
            if (currentTable != null) loadTableData(currentTable);
        });

        Button insertBtn = new Button("新增行");
        insertBtn.setOnAction(e -> insertRow());

        Button deleteBtn = new Button("删除行");
        deleteBtn.setOnAction(e -> deleteSelectedRow());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label hint = new Label("双击单元格可直接编辑");

        Button ledgerBtn = new Button("📋 缺陷台账");
        ledgerBtn.setStyle("-fx-background-color:#00adb5; -fx-text-fill:white; -fx-font-size:13; -fx-padding:6 16;");
        ledgerBtn.setOnAction(e -> SettingsScene.showDefectLedger(dbManager));

        HBox bar = new HBox(10, refreshBtn, insertBtn, deleteBtn, ledgerBtn, spacer, hint);
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    // ==================== 状态切换 ====================

    void setConnected(boolean connected) {
        if (connected) {
            dbStatus.setText("● 已连接 → " + dbManager.getCurrentDb());
            dbStatus.setStyle("-fx-text-fill:#28a745; -fx-font-size:13;");
            connectBtn.setDisable(true);
            disconnectBtn.setDisable(false);
        } else {
            dbStatus.setText("○ 未连接");
            dbStatus.setStyle("-fx-text-fill:#999; -fx-font-size:13;");
            connectBtn.setDisable(false);
            disconnectBtn.setDisable(true);
        }
    }

    void refreshTableList() {
        try {
            List<String> tables = new ArrayList<>(dbManager.getTables());
            if (!isAdmin) tables.removeIf(t -> t.equalsIgnoreCase("USERS"));
            tableList.setItems(FXCollections.observableArrayList(tables));
        } catch (Exception ignored) {}
    }

    // ==================== 数据操作 ====================

    private void loadTableData(String tableName) {
        if (!isAdmin && tableName.equalsIgnoreCase("USERS")) {
            FX.showAlert(Alert.AlertType.WARNING, "权限不足", "仅管理员可查看用户表");
            return;
        }
        try {
            List<Map<String, Object>> rows = dbManager.query("SELECT * FROM " + tableName);
            buildDynamicColumns(rows);
            dataTable.setItems(FXCollections.observableArrayList(rows));
        } catch (Exception e) {
            FX.showAlert(Alert.AlertType.ERROR, "加载失败", e.getMessage());
        }
    }

    /** 根据查询结果动态创建表格列 */
    private void buildDynamicColumns(List<Map<String, Object>> rows) {
        dataTable.getColumns().clear();
        if (rows.isEmpty()) return;
        Map<String, Object> firstRow = rows.get(0);
        for (String colName : firstRow.keySet()) {
            TableColumn<Map<String, Object>, String> tc = new TableColumn<>(colName);
            tc.setCellValueFactory(data -> {
                Object val = data.getValue().get(colName);
                return new SimpleStringProperty(val == null ? "" : val.toString());
            });
            applyEditableCell(tc);
            tc.setPrefWidth(Math.max(colName.length() * 14 + 20, 90));
            tc.setMaxWidth(400);
            dataTable.getColumns().add(tc);
        }
    }

    /** 使单元格可双击编辑，超长文本可点击查看 */
    private void applyEditableCell(TableColumn<Map<String, Object>, String> tc) {
        tc.setCellFactory(col -> new TableCell<>() {
            private TextField editor;
            {
                setWrapText(false);
                setMaxHeight(28);
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null); setTooltip(null); setOnMouseClicked(null);
                    return;
                }
                if (isEditing()) return;

                if (item.length() > 50) {
                    setText(item.substring(0, 47) + "...");
                    setTooltip(new Tooltip("双击编辑 / 单击查看全文 (共" + item.length() + "字)"));
                    setStyle("-fx-cursor:hand; -fx-text-overrun:ellipsis;");
                } else {
                    setText(item);
                    setTooltip(new Tooltip("双击编辑"));
                    setStyle("-fx-cursor:hand;");
                }

                setOnMouseClicked(ev -> {
                    if (ev.getClickCount() == 1 && item.length() > 50) {
                        showCellPopup(item);
                    }
                    if (ev.getClickCount() == 2) startEdit();
                });
            }

            @Override
            public void startEdit() {
                super.startEdit();
                if (editor == null) {
                    editor = new TextField();
                    editor.setOnAction(e -> commitEdit(editor.getText()));
                    editor.focusedProperty().addListener((obs, old, val) -> {
                        if (!val) commitEdit(editor.getText());
                    });
                }
                editor.setText(getItem() != null ? getItem() : "");
                setText(null); setGraphic(editor);
                editor.requestFocus(); editor.selectAll();
            }

            @Override
            public void commitEdit(String newValue) {
                super.commitEdit(newValue);
                Map<String, Object> row = getTableView().getItems().get(getIndex());
                String colName = getTableColumn().getText();
                String oldValue = row.get(colName) == null ? "" : row.get(colName).toString();
                if (!newValue.equals(oldValue)) {
                    row.put(colName, newValue);
                    if (currentTable != null && dbManager.isConnected()) {
                        try {
                            String pkCol = row.keySet().iterator().next();
                            String sql = "UPDATE " + currentTable + " SET " + colName
                                + " = " + FX.formatSqlValue(newValue)
                                + " WHERE " + pkCol + " = " + FX.formatSqlValue(row.get(pkCol));
                            dbManager.execute(sql);
                        } catch (Exception ignored) {}
                    }
                }
                setGraphic(null);
                updateItem(newValue, false);
            }

            @Override
            public void cancelEdit() {
                super.cancelEdit();
                setGraphic(null);
                updateItem(getItem(), false);
            }
        });
    }

    private void showCellPopup(String text) {
        Stage popup = new Stage();
        popup.setTitle("查看内容");
        popup.initModality(Modality.NONE);
        TextArea ta = new TextArea(text);
        ta.setWrapText(true);
        ta.setEditable(false);
        ta.setPrefWidth(550);
        ta.setPrefHeight(350);
        ta.setStyle("-fx-font-size:13; -fx-padding:8;");
        Scene scene = new Scene(new VBox(ta));
        scene.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ESCAPE) popup.close(); });
        popup.setScene(scene);
        popup.show();
    }

    private void insertRow() {
        if (!dbManager.isConnected()) {
            FX.showAlert(Alert.AlertType.WARNING, "提示", "请先连接数据库");
            return;
        }
        if (currentTable == null) {
            FX.showAlert(Alert.AlertType.WARNING, "提示", "请先选择一个表");
            return;
        }
        try {
            List<String> cols = dbManager.getColumns(currentTable);
            Dialog<ButtonType> dialog = new Dialog<>();
            dialog.setTitle("新增行 → " + currentTable);
            dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

            GridPane grid = new GridPane();
            grid.setHgap(10);
            grid.setVgap(8);
            TextField[] fields = new TextField[cols.size()];
            for (int i = 0; i < cols.size(); i++) {
                grid.add(new Label(cols.get(i)), 0, i);
                fields[i] = new TextField();
                grid.add(fields[i], 1, i);
            }
            dialog.getDialogPane().setContent(grid);

            dialog.showAndWait().ifPresent(btn -> {
                if (btn != ButtonType.OK) return;
                try {
                    StringBuilder colPart = new StringBuilder();
                    StringBuilder valPart = new StringBuilder();
                    boolean first = true;
                    for (int i = 0; i < cols.size(); i++) {
                        String val = fields[i].getText().trim();
                        if (val.isEmpty() && i == 0) continue;
                        if (!first) { colPart.append(","); valPart.append(","); }
                        colPart.append(cols.get(i));
                        valPart.append(FX.formatSqlValue(val));
                        first = false;
                    }
                    dbManager.execute("INSERT INTO " + currentTable
                        + " (" + colPart + ") VALUES (" + valPart + ")");
                    loadTableData(currentTable);
                } catch (Exception ex) {
                    FX.showAlert(Alert.AlertType.ERROR, "新增失败", ex.getMessage());
                }
            });
        } catch (Exception ex) {
            FX.showAlert(Alert.AlertType.ERROR, "错误", ex.getMessage());
        }
    }

    private void deleteSelectedRow() {
        if (!dbManager.isConnected() || currentTable == null) return;
        Map<String, Object> selected = dataTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            FX.showAlert(Alert.AlertType.WARNING, "提示", "请选中要删除的行");
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("确认删除");
        confirm.setHeaderText("确定要删除该行数据吗？");
        confirm.showAndWait().ifPresent(btn -> {
            if (btn != ButtonType.OK) return;
            try {
                String pkCol = selected.keySet().iterator().next();
                String sql = "DELETE FROM " + currentTable + " WHERE " + pkCol
                    + " = " + FX.formatSqlValue(selected.get(pkCol));
                dbManager.execute(sql);
                loadTableData(currentTable);
            } catch (Exception ex) {
                FX.showAlert(Alert.AlertType.ERROR, "删除失败", ex.getMessage());
            }
        });
    }
}
