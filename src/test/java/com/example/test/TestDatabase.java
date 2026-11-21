package com.example.test;

import com.example.config.DatabaseConfig;
import com.example.util.JpaUtil;
import com.example.util.MigrationExecutor;

import javax.persistence.EntityManagerFactory;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 测试工具：为每个测试类创建一个独立的 H2 内存数据库。
 *
 * <p>流程与生产环境完全一致：先执行迁移脚本，再启动 Hibernate（validate 模式），
 * 因此每次运行测试都会顺带验证迁移脚本与实体映射是否匹配。</p>
 */
final class TestDatabase {

    private TestDatabase() {
    }

    /** 生成唯一的内存库配置（DB_CLOSE_DELAY=-1：最后一个连接关闭后仍保留数据库） */
    static DatabaseConfig nouvelleBase() {
        String nom = "test_" + UUID.randomUUID().toString().replace("-", "");
        return new DatabaseConfig("jdbc:h2:mem:" + nom + ";DB_CLOSE_DELAY=-1", "sa", "");
    }

    /** 迁移 + 创建 EntityManagerFactory */
    static EntityManagerFactory creerEmf(DatabaseConfig config) {
        try {
            new MigrationExecutor(config.getJdbcUrl(), config.getUsername(), config.getPassword())
                    .executerToutesLesMigrations();
        } catch (MigrationExecutor.MigrationException e) {
            throw new IllegalStateException("Migration des tests impossible", e);
        }
        Map<String, Object> props = new HashMap<>();
        props.put("hibernate.show_sql", "false"); // 测试时关闭 SQL 输出
        return JpaUtil.creerEntityManagerFactory(config, props);
    }

    static EntityManagerFactory creerEmf() {
        return creerEmf(nouvelleBase());
    }
}
