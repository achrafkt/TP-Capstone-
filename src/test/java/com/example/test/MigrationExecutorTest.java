package com.example.test;

import com.example.config.DatabaseConfig;
import com.example.util.MigrationExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 迁移执行器测试：直接使用 JDBC，不依赖 JPA。
 */
class MigrationExecutorTest {

    private DatabaseConfig config;
    private MigrationExecutor executor;

    @BeforeEach
    void setUp() {
        config = TestDatabase.nouvelleBase(); // 每个测试一个全新的空数据库
        executor = new MigrationExecutor(config.getJdbcUrl(), config.getUsername(), config.getPassword());
    }

    @Test
    @DisplayName("Toutes les migrations sont appliquées dans l'ordre")
    void appliqueToutesLesMigrations() throws Exception {
        assertEquals(2, executor.executerToutesLesMigrations());
        assertEquals(2, compter("SELECT COUNT(*) FROM schema_version"));
        assertTrue(colonneExiste("SALLES", "DESCRIPTION"), "v2.0 ajoute salles.description");
    }

    @Test
    @DisplayName("Idempotence : une seconde exécution n'applique rien")
    void idempotence() throws Exception {
        executor.executerToutesLesMigrations();
        assertEquals(0, executor.executerToutesLesMigrations());
        assertFalse(executor.executeMigration("db/migration/v2_0_add_description.sql"));
    }

    @Test
    @DisplayName("v2.0 : l'index unique sur l'email est effectif")
    void indexUniqueEmail() throws Exception {
        executor.executerToutesLesMigrations();
        executer("INSERT INTO utilisateurs (email, nom) VALUES ('x@example.com', 'X')");
        assertThrows(SQLException.class,
                () -> executer("INSERT INTO utilisateurs (email, nom) VALUES ('x@example.com', 'Y')"));
    }

    @Test
    @DisplayName("Avant v2.0 l'email n'est pas unique (la migration apporte bien le changement)")
    void avantV2() throws Exception {
        executor.executeMigration("db/migration/v1_0_schema_initial.sql");
        assertFalse(colonneExiste("SALLES", "DESCRIPTION"));
        executer("INSERT INTO utilisateurs (email, nom) VALUES ('x@example.com', 'X')");
        executer("INSERT INTO utilisateurs (email, nom) VALUES ('x@example.com', 'Y')");
        // 此时执行 v2.0 会失败（存在重复邮箱）→ 必须先清理数据
        assertThrows(MigrationExecutor.MigrationException.class,
                () -> executor.executeMigration("db/migration/v2_0_add_description.sql"));
    }

    @Test
    @DisplayName("Erreur dans un script : rollback et version non enregistrée")
    void rollbackSurErreur() throws Exception {
        executor.executerToutesLesMigrations();

        assertThrows(MigrationExecutor.MigrationException.class,
                () -> executor.executeMigration("db/migration/v9_0_test_rollback.sql"));

        assertEquals(0, compter("SELECT COUNT(*) FROM equipements WHERE nom = 'Equipement fantome'"),
                "Le premier INSERT doit avoir été annulé");
        assertEquals(0, compter("SELECT COUNT(*) FROM schema_version WHERE version = '9.0'"));
    }

    @Test
    @DisplayName("Script introuvable : exception explicite")
    void scriptIntrouvable() {
        MigrationExecutor.MigrationException ex = assertThrows(MigrationExecutor.MigrationException.class,
                () -> executor.executeMigration("db/migration/v8_0_inexistant.sql"));
        assertTrue(ex.getMessage().contains("introuvable"));
    }

    @Test
    @DisplayName("Découpage du script : commentaires ignorés, ';' dans une chaîne conservé")
    void decoupage() throws Exception {
        // 通过反射调用包级私有的静态方法
        java.lang.reflect.Method m = MigrationExecutor.class.getDeclaredMethod("decouperEnInstructions", String.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> instructions = (List<String>) m.invoke(null,
                "-- commentaire ; avec point-virgule\n"
                        + "CREATE TABLE t (a VARCHAR(10));\n"
                        + "INSERT INTO t VALUES ('a;b'); -- fin de ligne\n"
                        + "\n");
        assertEquals(2, instructions.size());
        assertEquals("CREATE TABLE t (a VARCHAR(10))", instructions.get(0));
        assertEquals("INSERT INTO t VALUES ('a;b')", instructions.get(1));
    }

    // ===== JDBC 辅助方法 =====

    private Connection connexion() throws SQLException {
        return DriverManager.getConnection(config.getJdbcUrl(), config.getUsername(), config.getPassword());
    }

    private void executer(String sql) throws SQLException {
        try (Connection c = connexion(); Statement s = c.createStatement()) {
            s.executeUpdate(sql);
        }
    }

    private long compter(String sql) throws SQLException {
        try (Connection c = connexion(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private boolean colonneExiste(String table, String colonne) throws SQLException {
        try (Connection c = connexion(); ResultSet rs = c.getMetaData().getColumns(null, null, table, colonne)) {
            return rs.next();
        }
    }
}
