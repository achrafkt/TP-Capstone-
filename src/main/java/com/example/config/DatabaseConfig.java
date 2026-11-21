package com.example.config;

import java.util.HashMap;
import java.util.Map;

/**
 * 数据库连接参数。
 *
 * <p>JDBC 迁移（{@link com.example.util.MigrationExecutor}）与 JPA 共用同一份配置，
 * 确保两者连接的是同一个数据库。</p>
 *
 * <p>读取顺序：JVM 系统属性 → 环境变量 → 默认值（H2 文件数据库）。</p>
 * <pre>
 *   -Ddb.url=jdbc:mysql://localhost:3306/reservation?createDatabaseIfNotExist=true
 *   -Ddb.user=root
 *   -Ddb.password=password
 * </pre>
 */
public final class DatabaseConfig {

    /** 默认使用 H2 文件数据库：无需安装任何数据库即可运行 */
    public static final String URL_PAR_DEFAUT = "jdbc:h2:./data/reservation";

    private final String jdbcUrl;
    private final String username;
    private final String password;

    public DatabaseConfig(String jdbcUrl, String username, String password) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
    }

    /** 从系统属性 / 环境变量构建配置 */
    public static DatabaseConfig depuisEnvironnement() {
        return new DatabaseConfig(
                lire("db.url", "DB_URL", URL_PAR_DEFAUT),
                lire("db.user", "DB_USER", "sa"),
                lire("db.password", "DB_PASSWORD", ""));
    }

    private static String lire(String propriete, String variableEnv, String defaut) {
        String valeur = System.getProperty(propriete);
        if (valeur == null) {
            valeur = System.getenv(variableEnv);
        }
        return valeur != null ? valeur : defaut;
    }

    /** 根据 JDBC URL 推断驱动类 */
    public String getDriver() {
        if (jdbcUrl.startsWith("jdbc:mysql:")) {
            return "com.mysql.cj.jdbc.Driver";
        }
        return "org.h2.Driver";
    }

    /** 转换为 JPA 属性，用于覆盖 persistence.xml 中的连接参数 */
    public Map<String, Object> toJpaProperties() {
        Map<String, Object> props = new HashMap<>();
        props.put("javax.persistence.jdbc.driver", getDriver());
        props.put("javax.persistence.jdbc.url", jdbcUrl);
        props.put("javax.persistence.jdbc.user", username);
        props.put("javax.persistence.jdbc.password", password);
        return props;
    }

    public String getJdbcUrl() { return jdbcUrl; }

    public String getUsername() { return username; }

    public String getPassword() { return password; }
}
