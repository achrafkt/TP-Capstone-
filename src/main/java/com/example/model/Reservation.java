package com.example.model;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.NamedQuery;
import javax.persistence.PrePersist;
import javax.persistence.PreUpdate;
import javax.persistence.Table;
import javax.persistence.Version;
import java.time.LocalDateTime;

/**
 * 预订实体：某个用户在某个时间段占用某个会议室。
 *
 * <p>不放入二级缓存：预订是频繁变化的事务性数据，缓存收益低、失效成本高。</p>
 */
@Entity
@Table(name = "reservations")
@NamedQuery(name = "Reservation.findAvecDetails",
        // JOIN FETCH 一次性加载关联对象，避免 N+1 查询问题
        query = "SELECT r FROM Reservation r JOIN FETCH r.salle JOIN FETCH r.utilisateur "
                + "WHERE r.id = :id")
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 多对一：一个预订只对应一个会议室；LAZY 避免每次都加载会议室
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "salle_id", nullable = false)
    private Salle salle;

    // 多对一：一个预订只属于一个用户
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "utilisateur_id", nullable = false)
    private Utilisateur utilisateur;

    @Column(name = "date_debut", nullable = false)
    private LocalDateTime dateDebut;

    @Column(name = "date_fin", nullable = false)
    private LocalDateTime dateFin;

    // 预订事由（例如“项目周会”）
    @Column(length = 255)
    private String motif;

    // 以字符串形式保存枚举，避免枚举顺序变化导致数据错乱
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StatutReservation statut = StatutReservation.CONFIRMEE;

    @Column(name = "date_creation", nullable = false, updatable = false)
    private LocalDateTime dateCreation;

    // 乐观锁：两个事务同时修改同一预订时，后提交者会收到 OptimisticLockException
    @Version
    @Column(nullable = false)
    private int version;

    public Reservation() {
    }

    public Reservation(Utilisateur utilisateur, Salle salle,
                       LocalDateTime dateDebut, LocalDateTime dateFin, String motif) {
        this.utilisateur = utilisateur;
        this.salle = salle;
        this.dateDebut = dateDebut;
        this.dateFin = dateFin;
        this.motif = motif;
    }

    /** 持久化前：设置创建时间并校验时间段 */
    @PrePersist
    void avantPersist() {
        if (dateCreation == null) {
            dateCreation = LocalDateTime.now();
        }
        verifierCreneau();
    }

    /** 更新前：再次校验时间段（最后一道防线） */
    @PreUpdate
    void avantMiseAJour() {
        verifierCreneau();
    }

    private void verifierCreneau() {
        if (dateDebut == null || dateFin == null || !dateFin.isAfter(dateDebut)) {
            throw new IllegalStateException("La date de fin doit être postérieure à la date de début");
        }
    }

    /** 判断两个时间段是否重叠：[debut1, fin1) 与 [debut2, fin2) */
    public boolean chevauche(LocalDateTime debut, LocalDateTime fin) {
        return dateDebut.isBefore(fin) && dateFin.isAfter(debut);
    }

    // ===== Getters / Setters =====

    public Long getId() { return id; }

    public Salle getSalle() { return salle; }

    public void setSalle(Salle salle) { this.salle = salle; }

    public Utilisateur getUtilisateur() { return utilisateur; }

    public void setUtilisateur(Utilisateur utilisateur) { this.utilisateur = utilisateur; }

    public LocalDateTime getDateDebut() { return dateDebut; }

    public void setDateDebut(LocalDateTime dateDebut) { this.dateDebut = dateDebut; }

    public LocalDateTime getDateFin() { return dateFin; }

    public void setDateFin(LocalDateTime dateFin) { this.dateFin = dateFin; }

    public String getMotif() { return motif; }

    public void setMotif(String motif) { this.motif = motif; }

    public StatutReservation getStatut() { return statut; }

    public void setStatut(StatutReservation statut) { this.statut = statut; }

    public LocalDateTime getDateCreation() { return dateCreation; }

    public int getVersion() { return version; }

    @Override
    public String toString() {
        // 注意：不访问懒加载关联，避免在 EntityManager 关闭后触发 LazyInitializationException
        return "Reservation{id=" + id + ", debut=" + dateDebut + ", fin=" + dateFin
                + ", statut=" + statut + ", version=" + version + "}";
    }
}
