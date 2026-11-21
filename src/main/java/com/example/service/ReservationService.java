package com.example.service;

import com.example.model.Reservation;
import com.example.model.Salle;
import com.example.model.StatutReservation;
import com.example.model.Utilisateur;
import com.example.repository.ReservationRepository;
import com.example.util.TransactionHelper;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.LockModeType;
import javax.persistence.OptimisticLockException;
import javax.persistence.PersistenceException;
import javax.persistence.RollbackException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 预订业务服务：创建、修改、取消、删除预订。
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>每个公开方法 = 一个工作单元 = 一个 EntityManager + 一个事务；</li>
 *   <li>业务规则（时间段合法、无冲突）在服务层校验，并抛出清晰的业务异常；</li>
 *   <li>乐观锁冲突被转换为 {@link ModificationConcurrenteException}；</li>
 *   <li>所有成功与失败都会记录日志（java.util.logging）。</li>
 * </ul>
 */
public class ReservationService {

    private static final Logger LOGGER = Logger.getLogger(ReservationService.class.getName());

    private final EntityManagerFactory emf;

    public ReservationService(EntityManagerFactory emf) {
        this.emf = emf;
    }

    // ==================================================================
    // 创建
    // ==================================================================

    /**
     * 创建预订。
     *
     * <p>隔离性：先对会议室行加悲观写锁（SELECT ... FOR UPDATE），
     * 使同一会议室的“检查冲突 + 插入”串行执行，防止两个并发请求
     * 同时通过冲突检测而产生重复预订（写偏斜问题）。</p>
     */
    public Reservation creerReservation(Long utilisateurId, Long salleId,
                                        LocalDateTime debut, LocalDateTime fin, String motif) {
        validerCreneau(debut, fin);
        try {
            Reservation reservation = TransactionHelper.executer(emf, em -> {
                Utilisateur utilisateur = trouverOuEchouer(em, Utilisateur.class, utilisateurId, "Utilisateur");
                Salle salle = em.find(Salle.class, salleId, LockModeType.PESSIMISTIC_WRITE);
                if (salle == null) {
                    throw new ReservationException("Salle introuvable : id=" + salleId);
                }

                ReservationRepository repo = new ReservationRepository(em);
                if (repo.existeConflit(salleId, debut, fin, null)) {
                    throw new ConflitReservationException(String.format(
                            "La salle « %s » est déjà réservée entre %s et %s", salle.getNom(), debut, fin));
                }

                Reservation r = new Reservation(utilisateur, salle, debut, fin, motif);
                em.persist(r);
                return r;
            });
            LOGGER.info(String.format("Réservation #%d créée (salle=%d, utilisateur=%d, %s -> %s)",
                    reservation.getId(), salleId, utilisateurId, debut, fin));
            return reservation;
        } catch (ReservationException e) {
            LOGGER.warning("Création refusée : " + e.getMessage());
            throw e;
        } catch (PersistenceException e) {
            LOGGER.log(Level.SEVERE, "Erreur technique lors de la création de la réservation", e);
            throw new ReservationException("Impossible de créer la réservation : " + e.getMessage(), e);
        }
    }

    /** TP 第 11 步示例的签名：直接持久化一个已组装好的预订对象 */
    public Reservation createReservation(Reservation reservation) {
        return creerReservation(reservation.getUtilisateur().getId(), reservation.getSalle().getId(),
                reservation.getDateDebut(), reservation.getDateFin(), reservation.getMotif());
    }

    // ==================================================================
    // 修改
    // ==================================================================

    /**
     * 修改预订的时间段。
     *
     * @param versionConnue 调用方读取数据时看到的版本号；若数据库中的版本已变化，
     *                      说明他人已修改过该预订 → 抛出 {@link ModificationConcurrenteException}
     */
    public Reservation modifierCreneau(Long reservationId, LocalDateTime nouveauDebut,
                                       LocalDateTime nouvelleFin, int versionConnue) {
        validerCreneau(nouveauDebut, nouvelleFin);
        try {
            Reservation r = TransactionHelper.executer(emf, em -> {
                Reservation existante = trouverOuEchouer(em, Reservation.class, reservationId, "Réservation");
                if (existante.getVersion() != versionConnue) {
                    throw new OptimisticLockException("Version attendue " + versionConnue
                            + " mais version actuelle " + existante.getVersion(), null, existante);
                }
                if (existante.getStatut() == StatutReservation.ANNULEE) {
                    throw new ReservationException("Impossible de modifier une réservation annulée");
                }
                Long salleId = existante.getSalle().getId();
                em.find(Salle.class, salleId, LockModeType.PESSIMISTIC_WRITE);
                if (new ReservationRepository(em).existeConflit(salleId, nouveauDebut, nouvelleFin, reservationId)) {
                    throw new ConflitReservationException("Le nouveau créneau est en conflit avec une autre réservation");
                }
                existante.setDateDebut(nouveauDebut);
                existante.setDateFin(nouvelleFin);
                return existante;
            });
            LOGGER.info("Réservation #" + reservationId + " déplacée vers " + nouveauDebut + " -> " + nouvelleFin);
            return r;
        } catch (RuntimeException e) {
            throw traduire(e, "modification de la réservation #" + reservationId);
        }
    }

    /**
     * 合并一个“游离”（detached）的预订对象。
     * 若该对象的 @Version 已过期，Hibernate 会在 merge/flush 时抛出 OptimisticLockException。
     */
    public Reservation mettreAJour(Reservation detachee) {
        validerCreneau(detachee.getDateDebut(), detachee.getDateFin());
        try {
            Reservation r = TransactionHelper.executer(emf, em -> {
                Reservation fusionnee = em.merge(detachee);
                em.flush(); // 立即 flush，让版本冲突在此处暴露
                return fusionnee;
            });
            LOGGER.info("Réservation #" + r.getId() + " mise à jour (version " + r.getVersion() + ")");
            return r;
        } catch (RuntimeException e) {
            throw traduire(e, "mise à jour de la réservation #" + detachee.getId());
        }
    }

    // ==================================================================
    // 取消 / 删除
    // ==================================================================

    /** 取消预订：保留历史记录，只修改状态（释放会议室） */
    public void annulerReservation(Long reservationId) {
        try {
            TransactionHelper.executerSansResultat(emf, em -> {
                Reservation r = trouverOuEchouer(em, Reservation.class, reservationId, "Réservation");
                r.setStatut(StatutReservation.ANNULEE);
            });
            LOGGER.info("Réservation #" + reservationId + " annulée");
        } catch (RuntimeException e) {
            throw traduire(e, "annulation de la réservation #" + reservationId);
        }
    }

    /** 物理删除预订 */
    public void supprimerReservation(Long reservationId) {
        try {
            TransactionHelper.executerSansResultat(emf, em -> {
                Reservation r = trouverOuEchouer(em, Reservation.class, reservationId, "Réservation");
                em.remove(r);
            });
            LOGGER.info("Réservation #" + reservationId + " supprimée");
        } catch (RuntimeException e) {
            throw traduire(e, "suppression de la réservation #" + reservationId);
        }
    }

    // ==================================================================
    // 查询
    // ==================================================================

    /** 读取预订及其会议室、用户（JOIN FETCH，关闭 EntityManager 后仍可访问关联） */
    public Reservation trouverAvecDetails(Long id) {
        return TransactionHelper.lire(emf, em -> em.createNamedQuery("Reservation.findAvecDetails", Reservation.class)
                .setParameter("id", id)
                .getResultStream()
                .findFirst()
                .orElse(null));
    }

    public List<Reservation> reservationsAVenir(String email) {
        return TransactionHelper.lire(emf, em -> new ReservationRepository(em).findAVenirPourUtilisateur(email));
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    private static void validerCreneau(LocalDateTime debut, LocalDateTime fin) {
        if (debut == null || fin == null) {
            throw new ReservationException("Les dates de début et de fin sont obligatoires");
        }
        if (!fin.isAfter(debut)) {
            throw new ReservationException("La date de fin doit être postérieure à la date de début");
        }
        if (debut.isBefore(LocalDateTime.now())) {
            throw new ReservationException("Impossible de réserver dans le passé");
        }
    }

    private static <T> T trouverOuEchouer(EntityManager em, Class<T> type, Long id, String libelle) {
        T entite = em.find(type, id);
        if (entite == null) {
            throw new ReservationException(libelle + " introuvable : id=" + id);
        }
        return entite;
    }

    /**
     * 把底层异常转换为业务异常，并记录日志。
     * RESOURCE_LOCAL 模式下，提交时的乐观锁失败会被包装在 RollbackException 中，
     * 因此需要沿着 cause 链查找 OptimisticLockException。
     */
    private static RuntimeException traduire(RuntimeException e, String operation) {
        if (e instanceof ReservationException) {
            LOGGER.warning("Échec de la " + operation + " : " + e.getMessage());
            return e;
        }
        if (contientConflitVersion(e)) {
            LOGGER.warning("Conflit de version détecté lors de la " + operation);
            return new ModificationConcurrenteException(
                    "Conflit détecté : la réservation a été modifiée par un autre utilisateur, veuillez réessayer.", e);
        }
        LOGGER.log(Level.SEVERE, "Erreur technique lors de la " + operation, e);
        return new ReservationException("Erreur technique lors de la " + operation + " : " + e.getMessage(), e);
    }

    private static boolean contientConflitVersion(Throwable t) {
        while (t != null) {
            if (t instanceof OptimisticLockException
                    || t instanceof org.hibernate.StaleStateException
                    || (t instanceof RollbackException && t.getCause() instanceof OptimisticLockException)) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }
}
