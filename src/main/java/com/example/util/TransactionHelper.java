package com.example.util;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.EntityTransaction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * RESOURCE_LOCAL 事务模板：统一处理 begin / commit / rollback / close。
 *
 * <p>避免在每个业务方法里重复写 try/catch/rollback 样板代码。</p>
 */
public final class TransactionHelper {

    private static final Logger LOGGER = Logger.getLogger(TransactionHelper.class.getName());

    private TransactionHelper() {
    }

    /** 在一个新的 EntityManager 和事务中执行操作，并返回结果 */
    public static <T> T executer(EntityManagerFactory emf, Function<EntityManager, T> travail) {
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        try {
            tx.begin();
            T resultat = travail.apply(em);
            tx.commit(); // 提交时才会 flush，乐观锁冲突通常在这里抛出
            return resultat;
        } catch (RuntimeException e) {
            // 任何异常都必须回滚，保证数据一致性
            if (tx.isActive()) {
                try {
                    tx.rollback();
                } catch (RuntimeException rollbackEx) {
                    LOGGER.log(Level.WARNING, "Échec du rollback", rollbackEx);
                }
            }
            throw e;
        } finally {
            em.close(); // 始终释放资源
        }
    }

    /** 无返回值版本 */
    public static void executerSansResultat(EntityManagerFactory emf, Consumer<EntityManager> travail) {
        executer(emf, em -> {
            travail.accept(em);
            return null;
        });
    }

    /** 只读操作：不开启事务，仅保证 EntityManager 被关闭 */
    public static <T> T lire(EntityManagerFactory emf, Function<EntityManager, T> lecture) {
        EntityManager em = emf.createEntityManager();
        try {
            return lecture.apply(em);
        } finally {
            em.close();
        }
    }
}
