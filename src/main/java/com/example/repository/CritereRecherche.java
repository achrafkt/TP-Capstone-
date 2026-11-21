package com.example.repository;

import com.example.model.StatutReservation;

import java.time.LocalDateTime;

/**
 * 预订的动态搜索条件（所有字段可选，为 null 表示不过滤）。
 *
 * <p>配合 Criteria API 使用：只有非空条件才会生成对应的 WHERE 子句。</p>
 */
public class CritereRecherche {

    private String nomSalle;
    private String emailUtilisateur;
    private LocalDateTime debutApres;
    private LocalDateTime debutAvant;
    private Integer capaciteMin;
    private StatutReservation statut;

    // 流式 API：new CritereRecherche().salle("Salle A").statut(CONFIRMEE)
    public CritereRecherche salle(String nom) { this.nomSalle = nom; return this; }

    public CritereRecherche utilisateur(String email) { this.emailUtilisateur = email; return this; }

    public CritereRecherche debutApres(LocalDateTime date) { this.debutApres = date; return this; }

    public CritereRecherche debutAvant(LocalDateTime date) { this.debutAvant = date; return this; }

    public CritereRecherche capaciteMin(Integer capacite) { this.capaciteMin = capacite; return this; }

    public CritereRecherche statut(StatutReservation statut) { this.statut = statut; return this; }

    public String getNomSalle() { return nomSalle; }

    public String getEmailUtilisateur() { return emailUtilisateur; }

    public LocalDateTime getDebutApres() { return debutApres; }

    public LocalDateTime getDebutAvant() { return debutAvant; }

    public Integer getCapaciteMin() { return capaciteMin; }

    public StatutReservation getStatut() { return statut; }
}
