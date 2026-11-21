package com.example.util;

import com.example.config.DatabaseConfig;

import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;
import java.util.HashMap;
import java.util.Map;

/**
 * EntityManagerFactory 工厂。
 *
 * <p>EntityManagerFactory 创建成本高、线程安全，整个应用只需一个；
 * EntityManager 轻量、非线程安全，每个工作单元创建一个。</p>
 */
public final class JpaUtil {

    public static final String PERSISTENCE_UNIT = "ReservationPU";

    private JpaUtil() {
    }

    public static EntityManagerFactory creerEntityManagerFactory(DatabaseConfig config) {
        return creerEntityManagerFactory(config, new HashMap<>());
    }

    /**
     * @param proprietesSupplementaires 额外的 Hibernate 属性（例如测试中关闭 show_sql）
     */
    public static EntityManagerFactory creerEntityManagerFactory(DatabaseConfig config,
                                                                 Map<String, Object> proprietesSupplementaires) {
        Map<String, Object> props = new HashMap<>(config.toJpaProperties());
        props.putAll(proprietesSupplementaires);
        // 允许通过 -Dhibernate.xxx=... 临时覆盖任意 Hibernate 配置
        System.getProperties().forEach((cle, valeur) -> {
            if (cle.toString().startsWith("hibernate.")) {
                props.put(cle.toString(), valeur);
            }
        });
        return Persistence.createEntityManagerFactory(PERSISTENCE_UNIT, props);
    }
}
