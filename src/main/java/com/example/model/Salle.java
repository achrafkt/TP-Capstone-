package com.example.model;

import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;

import javax.persistence.Cacheable;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.JoinTable;
import javax.persistence.ManyToMany;
import javax.persistence.NamedQuery;
import javax.persistence.OneToMany;
import javax.persistence.Table;
import javax.persistence.Version;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 会议室实体。
 *
 * <p>缓存策略：READ_WRITE —— 会议室信息读多写少，但仍可能被修改，
 * READ_WRITE 通过软锁保证缓存与数据库的一致性。</p>
 */
@Entity
@Table(name = "salles")
@Cacheable
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
@NamedQuery(name = "Salle.findAll", query = "SELECT s FROM Salle s ORDER BY s.nom")
public class Salle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String nom;

    // 容纳人数
    @Column(nullable = false)
    private int capacite;

    @Column(length = 100)
    private String batiment;

    private Integer etage;

    // 由迁移脚本 v2.0 新增的列
    @Column(length = 255)
    private String description;

    @Version
    @Column(nullable = false)
    private int version;

    // 多对多：一个会议室有多个设备，一个设备可存在于多个会议室
    // 集合本身也放入二级缓存（集合缓存只保存 id，实体从实体缓存中读取）
    @ManyToMany
    @JoinTable(name = "salle_equipement",
            joinColumns = @JoinColumn(name = "salle_id"),
            inverseJoinColumns = @JoinColumn(name = "equipement_id"))
    @Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
    private Set<Equipement> equipements = new HashSet<>();

    // 反向关系：一个会议室有多个预订
    @OneToMany(mappedBy = "salle")
    private List<Reservation> reservations = new ArrayList<>();

    public Salle() {
    }

    public Salle(String nom, int capacite, String batiment, Integer etage) {
        this.nom = nom;
        this.capacite = capacite;
        this.batiment = batiment;
        this.etage = etage;
    }

    /** 辅助方法：添加设备 */
    public void ajouterEquipement(Equipement equipement) {
        equipements.add(equipement);
    }

    // ===== Getters / Setters =====

    public Long getId() { return id; }

    public String getNom() { return nom; }

    public void setNom(String nom) { this.nom = nom; }

    public int getCapacite() { return capacite; }

    public void setCapacite(int capacite) { this.capacite = capacite; }

    public String getBatiment() { return batiment; }

    public void setBatiment(String batiment) { this.batiment = batiment; }

    public Integer getEtage() { return etage; }

    public void setEtage(Integer etage) { this.etage = etage; }

    public String getDescription() { return description; }

    public void setDescription(String description) { this.description = description; }

    public int getVersion() { return version; }

    public Set<Equipement> getEquipements() { return equipements; }

    public List<Reservation> getReservations() { return reservations; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Salle)) return false;
        return nom != null && nom.equals(((Salle) o).nom);
    }

    @Override
    public int hashCode() { return Objects.hashCode(nom); }

    @Override
    public String toString() {
        return "Salle{id=" + id + ", nom='" + nom + "', capacite=" + capacite + "}";
    }
}
