package com.example.model;

import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;

import javax.persistence.Cacheable;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.NamedQuery;
import javax.persistence.OneToMany;
import javax.persistence.Table;
import javax.persistence.Version;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 用户实体：可以预订会议室的人。
 *
 * <p>缓存策略：NONSTRICT_READ_WRITE —— 用户资料很少修改，
 * 可以容忍短暂的缓存不一致，换取更低的锁开销。</p>
 */
@Entity
@Table(name = "utilisateurs")
@Cacheable
@Cache(usage = CacheConcurrencyStrategy.NONSTRICT_READ_WRITE)
@NamedQuery(name = "Utilisateur.findByEmail",
        query = "SELECT u FROM Utilisateur u WHERE u.email = :email")
public class Utilisateur {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY) // 主键由数据库自增生成
    private Long id;

    // 邮箱必须唯一且非空（数据库层面的完整性约束）
    @Column(nullable = false, unique = true, length = 150)
    private String email;

    @Column(nullable = false, length = 100)
    private String nom;

    @Column(length = 100)
    private String departement;

    // 乐观锁版本号：每次更新自动 +1，用于检测并发修改冲突
    @Version
    @Column(nullable = false)
    private int version;

    // 反向关系：一个用户可以有多个预订（懒加载，避免不必要的查询）
    @OneToMany(mappedBy = "utilisateur")
    private List<Reservation> reservations = new ArrayList<>();

    public Utilisateur() {
        // JPA 规范要求的无参构造函数
    }

    public Utilisateur(String nom, String email, String departement) {
        this.nom = nom;
        this.email = email;
        this.departement = departement;
    }

    // ===== Getters / Setters =====

    public Long getId() { return id; }

    public String getEmail() { return email; }

    public void setEmail(String email) { this.email = email; }

    public String getNom() { return nom; }

    public void setNom(String nom) { this.nom = nom; }

    public String getDepartement() { return departement; }

    public void setDepartement(String departement) { this.departement = departement; }

    public int getVersion() { return version; }

    public List<Reservation> getReservations() { return reservations; }

    // 以业务键（邮箱）判断相等，避免依赖尚未生成的 id
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Utilisateur)) return false;
        return email != null && email.equals(((Utilisateur) o).email);
    }

    @Override
    public int hashCode() { return Objects.hashCode(email); }

    @Override
    public String toString() {
        return "Utilisateur{id=" + id + ", nom='" + nom + "', email='" + email + "'}";
    }
}
