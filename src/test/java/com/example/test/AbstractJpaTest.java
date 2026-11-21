package com.example.test;

import com.example.model.Salle;
import com.example.model.Utilisateur;
import com.example.util.DataInitializer;
import com.example.util.TransactionHelper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;

/**
 * 测试基类：管理 EntityManagerFactory / EntityManager 的生命周期，
 * 并在每个测试前把数据库重置为同一份初始数据（测试之间互不影响）。
 */
abstract class AbstractJpaTest {

    protected static EntityManagerFactory emf;
    protected EntityManager em;

    @BeforeAll
    static void initEmf() {
        emf = TestDatabase.creerEmf();
    }

    @AfterAll
    static void closeEmf() {
        if (emf != null) {
            emf.close();
        }
    }

    @BeforeEach
    void setUp() {
        reinitialiserDonnees();
        em = emf.createEntityManager();
    }

    @AfterEach
    void tearDown() {
        if (em != null) {
            if (em.getTransaction().isActive()) {
                em.getTransaction().rollback();
            }
            em.close();
        }
    }

    /** 清空所有表、清空二级缓存，然后重新插入初始数据 */
    protected void reinitialiserDonnees() {
        TransactionHelper.executerSansResultat(emf, e -> {
            // 按外键依赖顺序删除（使用原生 SQL，因为 Equipement 是 @Immutable）
            e.createNativeQuery("DELETE FROM reservations").executeUpdate();
            e.createNativeQuery("DELETE FROM salle_equipement").executeUpdate();
            e.createNativeQuery("DELETE FROM salles").executeUpdate();
            e.createNativeQuery("DELETE FROM equipements").executeUpdate();
            e.createNativeQuery("DELETE FROM utilisateurs").executeUpdate();
        });
        emf.getCache().evictAll(); // 原生 SQL 绕过了 Hibernate，必须手动清空二级缓存

        EntityManager init = emf.createEntityManager();
        try {
            new DataInitializer(init).initData();
        } finally {
            init.close();
        }
    }

    // ===== 便捷查询方法 =====

    protected Salle salle(String nom) {
        return TransactionHelper.lire(emf, e -> e.createQuery("SELECT s FROM Salle s WHERE s.nom = :n", Salle.class)
                .setParameter("n", nom).getSingleResult());
    }

    protected Utilisateur utilisateur(String email) {
        return TransactionHelper.lire(emf, e -> e.createNamedQuery("Utilisateur.findByEmail", Utilisateur.class)
                .setParameter("email", email).getSingleResult());
    }
}
