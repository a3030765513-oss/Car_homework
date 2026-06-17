package com.substation.common.sql;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * SQL Server 数据库连接管理。
 * 负责建立 JDBC 连接、初始化数据库表、插入预设管理员账号。
 */
public class DatabaseManager {

    private static final Logger log = LoggerFactory.getLogger(DatabaseManager.class);

    private final String jdbcUrl;
    private final String user;
    private final String password;

    public static final String DB_URL = "jdbc:sqlserver://localhost:1433;databaseName=CarHomework;encrypt=false;trustServerCertificate=true";
    public static final String DB_USER = "sa";
    public static final String DB_PASSWORD = "Root@1234";

    public DatabaseManager() {
        this(DB_URL, DB_USER, DB_PASSWORD);
    }

    public DatabaseManager(String jdbcUrl, String user, String password) {
        this.jdbcUrl = jdbcUrl;
        this.user = user;
        this.password = password;
        try {
            Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("SQL Server JDBC 驱动未找到", e);
        }
    }

    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, user, password);
    }

    /** 系统启动时初始化：建表（幂等）+ 插入预设管理员 */
    public void initDatabase() {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(SQL_CREATE_TABLES);
            log.info("数据库表初始化完成 (幂等)");
        } catch (SQLException e) {
            log.warn("数据库初始化警告 (表可能已存在): {}", e.getMessage());
        }
        insertPresetAdmin();
    }

    /** 插入预设管理员账号 (幂等) */
    private void insertPresetAdmin() {
        String checkSql = "SELECT COUNT(*) FROM users WHERE username='admin'";
        String insertSql = "INSERT INTO users (username, password, role, display_name) VALUES ('admin', ?, 'admin', N'管理员')";
        try (Connection conn = getConnection();
             var ps = conn.prepareStatement(checkSql)) {
            var rs = ps.executeQuery();
            if (rs.next() && rs.getInt(1) > 0) {
                return; // 已存在
            }
        } catch (SQLException e) {
            log.warn("检查管理员账号失败: {}", e.getMessage());
            return;
        }
        try (Connection conn = getConnection();
             var ps = conn.prepareStatement(insertSql)) {
            ps.setString(1, org.mindrot.jbcrypt.BCrypt.hashpw("admin123", org.mindrot.jbcrypt.BCrypt.gensalt()));
            ps.executeUpdate();
            log.info("预设管理员账号 admin 已创建");
        } catch (SQLException e) {
            log.warn("创建管理员账号失败: {}", e.getMessage());
        }
    }

    private static final String SQL_CREATE_TABLES = """
        IF NOT EXISTS (SELECT 1 FROM sys.tables WHERE name='users')
        CREATE TABLE users (
            id INT IDENTITY(1,1) PRIMARY KEY,
            username NVARCHAR(50) NOT NULL UNIQUE,
            password NVARCHAR(200) NOT NULL,
            role NVARCHAR(20) NOT NULL DEFAULT 'simulator',
            display_name NVARCHAR(50) NULL,
            status NVARCHAR(20) NOT NULL DEFAULT 'active',
            created_at DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
        );

        IF NOT EXISTS (SELECT 1 FROM sys.tables WHERE name='registration_requests')
        CREATE TABLE registration_requests (
            id INT IDENTITY(1,1) PRIMARY KEY,
            username NVARCHAR(50) NOT NULL,
            password NVARCHAR(200) NOT NULL,
            role NVARCHAR(20) NOT NULL,
            display_name NVARCHAR(50) NULL,
            status NVARCHAR(20) NOT NULL DEFAULT 'pending',
            reviewed_by NVARCHAR(50) NULL,
            review_time DATETIME2 NULL,
            created_at DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
        );

        IF NOT EXISTS (SELECT 1 FROM sys.tables WHERE name='operation_logs')
        CREATE TABLE operation_logs (
            id INT IDENTITY(1,1) PRIMARY KEY,
            username NVARCHAR(50) NOT NULL,
            action NVARCHAR(50) NOT NULL,
            target NVARCHAR(200) NULL,
            details NVARCHAR(500) NULL,
            ip_address NVARCHAR(50) NULL,
            created_at DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
        );
        """;
}
