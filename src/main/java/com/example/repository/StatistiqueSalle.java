package com.example.repository;

/**
 * 统计结果 DTO：由 JPQL 构造器表达式 {@code SELECT NEW ...} 直接创建，
 * 不是实体，因此不会进入持久化上下文。
 */
public class StatistiqueSalle {

    private final String nomSalle;
    private final int capacite;
    private final long nombreReservations;

    public StatistiqueSalle(String nomSalle, int capacite, long nombreReservations) {
        this.nomSalle = nomSalle;
        this.capacite = capacite;
        this.nombreReservations = nombreReservations;
    }

    public String getNomSalle() { return nomSalle; }

    public int getCapacite() { return capacite; }

    public long getNombreReservations() { return nombreReservations; }

    @Override
    public String toString() {
        return String.format("%-18s (cap. %3d) : %d réservation(s)", nomSalle, capacite, nombreReservations);
    }
}
