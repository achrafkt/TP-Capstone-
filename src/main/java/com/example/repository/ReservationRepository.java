package com.example.repository;

import com.example.model.Reservation;
import com.example.model.Salle;
import com.example.model.StatutReservation;
import com.example.model.Utilisateur;

import javax.persistence.EntityManager;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Fetch;
import javax.persistence.criteria.Join;
import javax.persistence.criteria.JoinType;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 数据访问层：集中存放所有 JPQL 与 Criteria API 查询。
 *
 * <p>所有查询均使用参数绑定（:param），从根本上杜绝 SQL 注入。
 * 本类不管理事务，由调用方（Service）提供 EntityManager。</p>
 */
public class ReservationRepository {

    private final EntityManager em;

    public ReservationRepository(EntityManager em) {
        this.em = em;
    }

    // ==================================================================
    // JPQL
    // ==================================================================

    /** TP 示例：某会议室在指定日期之后的预订（JPQL 版本） */
    public List<Reservation> findBySalleEtDateApres(String nomSalle, LocalDateTime date) {
        String jpql = "SELECT r FROM Reservation r "
                + "WHERE r.salle.nom = :nomSalle AND r.dateDebut > :date "
                + "ORDER BY r.dateDebut";
        return em.createQuery(jpql, Reservation.class)
                .setParameter("nomSalle", nomSalle)
                .setParameter("date", date)
                .getResultList();
    }

    /**
     * 冲突检测：同一会议室中是否存在与 [debut, fin) 重叠的有效预订。
     * 两个区间重叠的条件：已有.debut &lt; 新.fin 且 已有.fin &gt; 新.debut
     *
     * @param idExclu 修改预订时排除自身（新建时传 null）
     */
    public boolean existeConflit(Long salleId, LocalDateTime debut, LocalDateTime fin, Long idExclu) {
        Long nb = em.createQuery("SELECT COUNT(r) FROM Reservation r "
                        + "WHERE r.salle.id = :salleId "
                        + "AND r.statut <> :annulee "
                        + "AND r.dateDebut < :fin AND r.dateFin > :debut "
                        + "AND r.id <> :idExclu", Long.class)
                .setParameter("salleId", salleId)
                .setParameter("annulee", StatutReservation.ANNULEE)
                .setParameter("debut", debut)
                .setParameter("fin", fin)
                // id 由数据库自增生成，永远 > 0，因此 -1 表示“不排除任何预订”
                .setParameter("idExclu", idExclu != null ? idExclu : -1L)
                .getSingleResult();
        return nb > 0;
    }

    /** 某用户未来的有效预订；JOIN FETCH 预先加载会议室，避免 N+1 */
    public List<Reservation> findAVenirPourUtilisateur(String email) {
        return em.createQuery("SELECT r FROM Reservation r JOIN FETCH r.salle "
                        + "WHERE r.utilisateur.email = :email "
                        + "AND r.statut = :statut AND r.dateDebut > :maintenant "
                        + "ORDER BY r.dateDebut", Reservation.class)
                .setParameter("email", email)
                .setParameter("statut", StatutReservation.CONFIRMEE)
                .setParameter("maintenant", LocalDateTime.now())
                .getResultList();
    }

    /** 在给定时间段内、容量足够且空闲的会议室（NOT EXISTS 子查询） */
    public List<Salle> findSallesDisponibles(LocalDateTime debut, LocalDateTime fin, int capaciteMin) {
        return em.createQuery("SELECT s FROM Salle s WHERE s.capacite >= :cap "
                        + "AND NOT EXISTS (SELECT r FROM Reservation r WHERE r.salle = s "
                        + "  AND r.statut <> :annulee AND r.dateDebut < :fin AND r.dateFin > :debut) "
                        + "ORDER BY s.capacite", Salle.class)
                .setParameter("cap", capaciteMin)
                .setParameter("annulee", StatutReservation.ANNULEE)
                .setParameter("debut", debut)
                .setParameter("fin", fin)
                .getResultList();
    }

    /** 每个会议室的有效预订数量：构造器表达式 + LEFT JOIN + GROUP BY */
    public List<StatistiqueSalle> statistiquesParSalle() {
        return em.createQuery("SELECT NEW com.example.repository.StatistiqueSalle(s.nom, s.capacite, COUNT(r)) "
                        + "FROM Salle s LEFT JOIN s.reservations r ON r.statut <> :annulee "
                        + "GROUP BY s.id, s.nom, s.capacite "
                        + "ORDER BY COUNT(r) DESC, s.nom", StatistiqueSalle.class)
                .setParameter("annulee", StatutReservation.ANNULEE)
                .getResultList();
    }

    /** 全部会议室，结果放入查询缓存（第二次调用不访问数据库） */
    public List<Salle> findToutesLesSallesEnCache() {
        return em.createNamedQuery("Salle.findAll", Salle.class)
                .setHint("org.hibernate.cacheable", true)
                .getResultList();
    }

    // ==================================================================
    // Criteria API
    // ==================================================================

    /** TP 示例：与 {@link #findBySalleEtDateApres} 相同的查询，用 Criteria API 编写 */
    public List<Reservation> findBySalleEtDateApresCriteria(String nomSalle, LocalDateTime date) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Reservation> cq = cb.createQuery(Reservation.class);
        Root<Reservation> root = cq.from(Reservation.class);

        Predicate predicate = cb.and(
                cb.equal(root.get("salle").get("nom"), nomSalle),
                cb.greaterThan(root.<LocalDateTime>get("dateDebut"), date));

        cq.select(root).where(predicate).orderBy(cb.asc(root.get("dateDebut")));
        return em.createQuery(cq).getResultList();
    }

    /**
     * 动态搜索：根据 {@link CritereRecherche} 中非空的字段拼装 WHERE 条件。
     * 这正是 Criteria API 相比字符串拼接 JPQL 的优势：类型安全、无注入风险。
     */
    public List<Reservation> rechercher(CritereRecherche critere) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Reservation> cq = cb.createQuery(Reservation.class);
        Root<Reservation> r = cq.from(Reservation.class);

        // fetch 同时充当 join，一次性加载会议室与用户（Hibernate 的 Fetch 实现同时也是 Join）
        Fetch<Reservation, Salle> fetchSalle = r.fetch("salle", JoinType.INNER);
        Fetch<Reservation, Utilisateur> fetchUtilisateur = r.fetch("utilisateur", JoinType.INNER);
        @SuppressWarnings("unchecked")
        Join<Reservation, Salle> salle = (Join<Reservation, Salle>) fetchSalle;
        @SuppressWarnings("unchecked")
        Join<Reservation, Utilisateur> utilisateur = (Join<Reservation, Utilisateur>) fetchUtilisateur;

        List<Predicate> predicats = new ArrayList<>();
        if (critere.getNomSalle() != null) {
            predicats.add(cb.equal(salle.get("nom"), critere.getNomSalle()));
        }
        if (critere.getEmailUtilisateur() != null) {
            predicats.add(cb.equal(utilisateur.get("email"), critere.getEmailUtilisateur()));
        }
        if (critere.getDebutApres() != null) {
            predicats.add(cb.greaterThanOrEqualTo(r.<LocalDateTime>get("dateDebut"), critere.getDebutApres()));
        }
        if (critere.getDebutAvant() != null) {
            predicats.add(cb.lessThan(r.<LocalDateTime>get("dateDebut"), critere.getDebutAvant()));
        }
        if (critere.getCapaciteMin() != null) {
            predicats.add(cb.greaterThanOrEqualTo(salle.<Integer>get("capacite"), critere.getCapaciteMin()));
        }
        if (critere.getStatut() != null) {
            predicats.add(cb.equal(r.get("statut"), critere.getStatut()));
        }

        cq.select(r)
                .where(predicats.toArray(new Predicate[0]))
                .orderBy(cb.asc(r.get("dateDebut")));
        return em.createQuery(cq).getResultList();
    }
}
