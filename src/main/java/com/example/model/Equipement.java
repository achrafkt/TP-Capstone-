package com.example.model;

import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;
import org.hibernate.annotations.Immutable;

import javax.persistence.Cacheable;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;
import java.util.Objects;

/**
 * 设备实体（投影仪、白板、视频会议……）。
 *
 * <p>这是参考数据，创建后不再修改，因此标记为 {@link Immutable}
 * 并使用 READ_ONLY 缓存策略 —— 性能最好的策略。</p>
 */
@Entity
@Table(name = "equipements")
@Immutable
@Cacheable
@Cache(usage = CacheConcurrencyStrategy.READ_ONLY)
public class Equipement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String nom;

    public Equipement() {
    }

    public Equipement(String nom) {
        this.nom = nom;
    }

    public Long getId() { return id; }

    public String getNom() { return nom; }

    public void setNom(String nom) { this.nom = nom; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Equipement)) return false;
        return nom != null && nom.equals(((Equipement) o).nom);
    }

    @Override
    public int hashCode() { return Objects.hashCode(nom); }

    @Override
    public String toString() { return nom; }
}
