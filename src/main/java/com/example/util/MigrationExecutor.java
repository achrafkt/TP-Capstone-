package com.example.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 通过 JDBC 执行 SQL 迁移脚本。
 *
 * <p>相比 TP 中的示例做了以下增强：</p>
 * <ul>
 *   <li>把脚本拆分成单条语句逐条执行（MySQL 默认不允许一次执行多条语句）；</li>
 *   <li>使用 {@code schema_version} 表记录已执行的版本，保证每个脚本只执行一次（幂等）；</li>
 *   <li>在事务中执行，出错时回滚（注意：MySQL 的 DDL 会隐式提交，无法真正回滚）。</li>
 * </ul>
 */
public class MigrationExecutor {

    private static final Logger LOGGER = Logger.getLogger(MigrationExecutor.class.getName());

    /** 按顺序排列的全部迁移脚本（classpath 路径） */
    public static final List<String> MIGRATIONS = Collections.unmodifiableList(Arrays.asList(
            "db/migration/v1_0_schema_initial.sql",
            "db/migration/v2_0_add_description.sql"));

    // 从文件名中提取版本号：v2_0_add_description.sql -> 2.0
    private static final Pattern VERSION_PATTERN = Pattern.compile("v(\\d+(?:_\\d+)*)__?[^/]*\\.sql$");

    private final String jdbcUrl;
    private final String username;
    private final String password;

    public MigrationExecutor(String jdbcUrl, String username, String password) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
    }

    /** 依次执行所有尚未执行的迁移，返回本次实际执行的脚本数 */
    public int executerToutesLesMigrations() throws MigrationException {
        int appliquees = 0;
        for (String script : MIGRATIONS) {
            if (executeMigration(script)) {
                appliquees++;
            }
        }
        LOGGER.info("Migrations terminées : " + appliquees + " script(s) appliqué(s)");
        return appliquees;
    }

    /**
     * 执行单个迁移脚本（若该版本已执行过则跳过）。
     *
     * @param scriptPath classpath 中的脚本路径，例如 {@code db/migration/v2_0_add_description.sql}
     * @return 本次是否真正执行了脚本
     */
    public boolean executeMigration(String scriptPath) throws MigrationException {
        String version = extraireVersion(scriptPath);

        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password)) {
            creerTableVersionSiAbsente(conn);

            if (estDejaAppliquee(conn, version)) {
                LOGGER.fine("Migration " + version + " déjà appliquée, ignorée");
                return false;
            }

            List<String> instructions = decouperEnInstructions(lireScript(scriptPath));
            LOGGER.info("Application de la migration " + version + " (" + instructions.size()
                    + " instruction(s)) : " + scriptPath);

            boolean autoCommitInitial = conn.getAutoCommit();
            conn.setAutoCommit(false); // 开启事务
            try (Statement stmt = conn.createStatement()) {
                for (String sql : instructions) {
                    LOGGER.fine("SQL> " + sql);
                    stmt.execute(sql);
                }
                enregistrerVersion(conn, version, scriptPath);
                conn.commit();
            } catch (SQLException e) {
                conn.rollback(); // 出错回滚，避免留下“半执行”状态
                throw new MigrationException("Échec de la migration " + version + " : " + e.getMessage(), e);
            } finally {
                conn.setAutoCommit(autoCommitInitial);
            }
            LOGGER.info("Migration " + version + " appliquée avec succès");
            return true;
        } catch (SQLException e) {
            throw new MigrationException("Erreur JDBC pendant la migration " + version + " : " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------
    // 内部方法
    // ------------------------------------------------------------------

    static String extraireVersion(String scriptPath) {
        Matcher m = VERSION_PATTERN.matcher(scriptPath);
        if (!m.find()) {
            throw new IllegalArgumentException("Nom de script invalide (attendu vX_Y_nom.sql) : " + scriptPath);
        }
        return m.group(1).replace('_', '.');
    }

    private String lireScript(String scriptPath) throws MigrationException {
        InputStream is = getClass().getClassLoader().getResourceAsStream(scriptPath);
        if (is == null) {
            throw new MigrationException("Script introuvable dans le classpath : " + scriptPath, null);
        }
        StringBuilder sql = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sql.append(line).append('\n');
            }
        } catch (IOException e) {
            throw new MigrationException("Lecture impossible du script " + scriptPath, e);
        }
        return sql.toString();
    }

    /**
     * 把 SQL 脚本拆分成单条语句：去掉 {@code --} 注释，按分号切分，
     * 并正确处理单引号字符串中的分号。
     */
    static List<String> decouperEnInstructions(String script) {
        List<String> resultat = new ArrayList<>();
        StringBuilder courante = new StringBuilder();
        boolean dansChaine = false;

        for (int i = 0; i < script.length(); i++) {
            char c = script.charAt(i);

            // 行注释：跳到行尾（字符串内部的 -- 不是注释）
            if (!dansChaine && c == '-' && i + 1 < script.length() && script.charAt(i + 1) == '-') {
                while (i < script.length() && script.charAt(i) != '\n') {
                    i++;
                }
                courante.append('\n');
                continue;
            }
            if (c == '\'') {
                dansChaine = !dansChaine;
            }
            if (c == ';' && !dansChaine) {
                ajouterSiNonVide(resultat, courante);
                courante.setLength(0);
            } else {
                courante.append(c);
            }
        }
        ajouterSiNonVide(resultat, courante);
        return resultat;
    }

    private static void ajouterSiNonVide(List<String> liste, StringBuilder sb) {
        String sql = sb.toString().trim();
        if (!sql.isEmpty()) {
            liste.add(sql);
        }
    }

    private void creerTableVersionSiAbsente(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS schema_version ("
                    + " version VARCHAR(50) NOT NULL PRIMARY KEY,"
                    + " script VARCHAR(255) NOT NULL,"
                    + " date_application TIMESTAMP NOT NULL)");
        }
    }

    private boolean estDejaAppliquee(Connection conn, String version) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM schema_version WHERE version = ?")) {
            ps.setString(1, version);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void enregistrerVersion(Connection conn, String version, String script) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO schema_version (version, script, date_application) VALUES (?, ?, ?)")) {
            ps.setString(1, version);
            ps.setString(2, script);
            ps.setTimestamp(3, Timestamp.valueOf(LocalDateTime.now()));
            ps.executeUpdate();
        }
    }

    /** 迁移失败时抛出的受检异常 */
    public static class MigrationException extends Exception {
        public MigrationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
