
import java.sql.*;
import java.security.MessageDigest;
import java.util.*;

/**
 * 数据库管理器
 *
 * 封装 H2 嵌入式数据库的连接、查询、更新操作。
 * 管理两张核心表：
 * - users：用户账户（用户名、密码哈希、角色）
 * - defects：缺陷检测记录（图片路径、检测结果JSON、AI分析、时间戳）
 *
 * 所有 SQL 操作使用 PreparedStatement 参数绑定，防止注入攻击。
 *
 * 数据库文件存储在项目目录 ./data/bridge.mv.db，零安装零配置。
 *
 * @author 长沙理工大学
 */
public class DBManager {

    // ==================== H2 连接常量 ====================

    /** H2 默认数据库 URL（文件模式，MySQL 兼容） */
    private static final String H2_DEFAULT_URL =
        "jdbc:h2:file:./data/bridge;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000";

    /** H2 URL 前缀（文件模式） */
    private static final String H2_URL_PREFIX = "jdbc:h2:file:./data/";

    /** H2 URL 后缀（MySQL 兼容 + 持久化参数） */
    private static final String H2_URL_SUFFIX =
        ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000";

    /** 当前数据库连接 */
    private Connection connection;

    /** 当前连接的数据库名称 */
    private String currentDb;

    // ==================== 密码工具 ====================

    /**
     * 使用 SHA-256 对密码进行哈希
     * 数据库中存储哈希值而非明文密码
     */
    public static String hashPassword(String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(password.getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            return password; // 极端情况下回退
        }
    }

    // ==================== 连接管理 ====================

    /**
     * 自动连接嵌入式 H2 数据库（零配置启动）
     * 数据库文件自动创建在 ./data/bridge.mv.db
     */
    public void connectEmbedded() throws SQLException {
        connection = DriverManager.getConnection(H2_DEFAULT_URL, "sa", "");
        currentDb = "bridge (H2)";
    }

    /**
     * 连接到 H2 嵌入式数据库
     *
     * @param host   保留用于 H2 Server 模式（当前忽略）
     * @param port   保留用于 H2 Server 模式（当前忽略）
     * @param dbName 数据库文件名（不含扩展名，如 "bridge"）
     * @param user   用户名（H2 默认为 "sa"）
     * @param pwd    密码（H2 默认为空）
     */
    public void connect(String host, String port, String dbName,
                         String user, String pwd) throws SQLException {
        String url = H2_URL_PREFIX + dbName + H2_URL_SUFFIX;
        connection = DriverManager.getConnection(url,
            (user == null || user.isEmpty()) ? "sa" : user,
            (pwd == null || pwd.isEmpty()) ? "" : pwd);
        currentDb = dbName + " (H2)";
    }

    /** 断开数据库连接 */
    public void disconnect() {
        if (connection != null) {
            try { connection.close(); } catch (SQLException ignored) {}
            connection = null;
            currentDb = null;
        }
    }

    /** 是否已连接到数据库 */
    public boolean isConnected() {
        return connection != null;
    }

    /** 获取当前数据库名称 */
    public String getCurrentDb() {
        return currentDb;
    }

    // ==================== 通用查询与更新 ====================

    /**
     * 执行 SELECT 查询，返回结果行列表
     * 每一行是一个 Map（列名 → 值）
     */
    public List<Map<String, Object>> query(String sql) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            ResultSetMetaData meta = rs.getMetaData();
            int colCount = meta.getColumnCount();

            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= colCount; i++) {
                    row.put(meta.getColumnName(i), rs.getObject(i));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    /**
     * 执行 UPDATE / INSERT / DELETE 语句
     *
     * @return 受影响的行数
     */
    public int execute(String sql) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            return stmt.executeUpdate(sql);
        }
    }

    /** 获取数据库中的所有表名 */
    public List<String> getTables() throws SQLException {
        List<String> tables = new ArrayList<>();
        DatabaseMetaData meta = connection.getMetaData();
        try (ResultSet rs = meta.getTables(null, "public", "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                tables.add(rs.getString("TABLE_NAME"));
            }
        }
        return tables;
    }

    /** 获取指定表的所有列名 */
    public List<String> getColumns(String tableName) throws SQLException {
        List<String> cols = new ArrayList<>();
        DatabaseMetaData meta = connection.getMetaData();
        try (ResultSet rs = meta.getColumns(null, "public", tableName, "%")) {
            while (rs.next()) {
                cols.add(rs.getString("COLUMN_NAME"));
            }
        }
        return cols;
    }

    // ==================== 缺陷记录表 ====================

    /** 确保 defects 表存在，不存在则自动创建 */
    public void ensureDefectTable() throws SQLException {
        execute("CREATE TABLE IF NOT EXISTS defects ("
            + "id INT AUTO_INCREMENT PRIMARY KEY,"
            + "image_path VARCHAR(500),"
            + "result_path VARCHAR(500),"
            + "defect_count INT,"
            + "defect_types VARCHAR(500),"
            + "detail_json TEXT,"
            + "ai_analysis TEXT,"
            + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
            + ")");
        // 兼容旧表：尝试添加 ai_analysis 列（列已存在则忽略）
        try {
            execute("ALTER TABLE defects ADD COLUMN ai_analysis TEXT");
        } catch (SQLException ignored) {
            // 列已存在，忽略 "Duplicate column" 错误
        }
    }

    /**
     * 保存一条缺陷检测记录（使用 PreparedStatement 防注入）
     * @return 自增主键 ID，失败返回 -1
     */
    public int saveDetection(String imagePath, String resultPath,
                              int count, String types, String detailJson)
                              throws SQLException {
        String sql = "INSERT INTO defects (image_path, result_path, defect_count, defect_types, detail_json)"
                   + " VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, imagePath);
            ps.setString(2, resultPath);
            ps.setInt(3, count);
            ps.setString(4, types);
            ps.setString(5, detailJson);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getInt(1);
            }
        }
        return -1;
    }

    /**
     * 更新缺陷记录的 AI 分析内容
     * @param id          记录 ID（由 saveDetection 返回）
     * @param aiAnalysis  AI 分析文本（可为 null）
     * @return 受影响行数
     */
    public int updateAiAnalysis(int id, String aiAnalysis) throws SQLException {
        String sql = "UPDATE defects SET ai_analysis = ? WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, aiAnalysis);
            ps.setInt(2, id);
            return ps.executeUpdate();
        }
    }

    /** 获取所有缺陷记录（按时间倒序） */
    public List<Map<String, Object>> getDetections() throws SQLException {
        return query("SELECT * FROM defects ORDER BY created_at DESC");
    }

    // ==================== 用户表 ====================

    /** 确保 users 表存在，并创建默认管理员 admin/admin */
    public void ensureUserTable() throws SQLException {
        execute("CREATE TABLE IF NOT EXISTS users ("
            + "id INT AUTO_INCREMENT PRIMARY KEY,"
            + "username VARCHAR(50) UNIQUE,"
            + "password VARCHAR(100),"
            + "role VARCHAR(20) DEFAULT 'user'"
            + ")");

        // 尝试创建默认管理员（如已存在则忽略）
        try {
            execute("INSERT INTO users (username, password, role) VALUES ('admin', '"
                + hashPassword("admin") + "', 'admin')");
        } catch (SQLException ignored) {
            // admin 用户已存在，无需重复创建
        }
    }

    /**
     * 验证用户登录（使用 PreparedStatement 防注入）
     *
     * @param username  用户名
     * @param password  明文密码
     * @return 验证是否通过
     */
    public boolean verifyLogin(String username, String password) throws SQLException {
        String hashed = hashPassword(password);

        // 先尝试哈希密码匹配
        String sql = "SELECT * FROM users WHERE username=? AND password=?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, hashed);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return true;
            }
        }

        // 兼容旧明文密码（匹配后自动升级为哈希）
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, password);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    // 自动升级为哈希密码
                    String updateSql = "UPDATE users SET password=? WHERE username=?";
                    try (PreparedStatement ups = connection.prepareStatement(updateSql)) {
                        ups.setString(1, hashed);
                        ups.setString(2, username);
                        ups.executeUpdate();
                    }
                    return true;
                }
            }
        }
        return false;
    }

    /** 获取所有用户列表（不包含密码） */
    public List<Map<String, Object>> listUsers() throws SQLException {
        return query("SELECT id, username, role FROM users ORDER BY id");
    }

    /** 添加新用户 */
    public void addUser(String username, String password, String role)
            throws SQLException {
        String sql = "INSERT INTO users (username, password, role) VALUES (?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, hashPassword(password));
            ps.setString(3, role);
            ps.executeUpdate();
        }
    }

    /** 删除用户（不能删除 admin） */
    public void deleteUser(String username) throws SQLException {
        String sql = "DELETE FROM users WHERE username=? AND username<>'admin'";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.executeUpdate();
        }
    }

    /** 重置用户密码 */
    public void resetPassword(String username, String newPassword)
            throws SQLException {
        String sql = "UPDATE users SET password=? WHERE username=?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, hashPassword(newPassword));
            ps.setString(2, username);
            ps.executeUpdate();
        }
    }

    /** 获取用户角色 */
    public String getUserRole(String username) throws SQLException {
        String sql = "SELECT role FROM users WHERE username=?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("role") : "user";
            }
        }
    }
}
