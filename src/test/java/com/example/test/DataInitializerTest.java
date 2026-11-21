package com.example.test;

import com.example.model.Reservation;
import com.example.model.StatutReservation;
import com.example.util.DataInitializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 测试数据生成器的测试：数量正确、数据自身无冲突、日期动态有效。
 */
class DataInitializerTest extends AbstractJpaTest {

    @Test
    @DisplayName("Volumes attendus")
    void volumes() {
        assertEquals(8L, compter("Utilisateur"));
        assertEquals(6L, compter("Salle"));
        assertEquals(6L, compter("Equipement"));
        assertTrue(compter("Reservation") >= 50, "Jeu de données suffisamment volumineux");
    }

    @Test
    @DisplayName("Les réservations du jeu de données ne se chevauchent pas")
    void aucunChevauchement() {
        List<Reservation> actives = em.createQuery("SELECT r FROM Reservation r JOIN FETCH r.salle "
                        + "WHERE r.statut <> :a", Reservation.class)
                .setParameter("a", StatutReservation.ANNULEE).getResultList();

        Map<Long, List<Reservation>> parSalle = actives.stream()
                .collect(Collectors.groupingBy(r -> r.getSalle().getId()));
        parSalle.values().forEach(liste -> {
            for (int i = 0; i < liste.size(); i++) {
                for (int j = i + 1; j < liste.size(); j++) {
                    Reservation a = liste.get(i);
                    Reservation b = liste.get(j);
                    assertFalse(a.chevauche(b.getDateDebut(), b.getDateFin()),
                            "Chevauchement entre " + a + " et " + b);
                }
            }
        });
    }

    @Test
    @DisplayName("Dates dynamiques : les réservations confirmées sont dans le futur")
    void datesDynamiques() {
        List<Reservation> confirmees = em.createQuery("SELECT r FROM Reservation r WHERE r.statut = :s",
                Reservation.class).setParameter("s", StatutReservation.CONFIRMEE).getResultList();
        LocalDateTime maintenant = LocalDateTime.now();
        assertTrue(confirmees.stream().allMatch(r -> r.getDateDebut().isAfter(maintenant.minusMinutes(1))));
        assertTrue(compterStatut(StatutReservation.TERMINEE) > 0, "Historique présent");
    }

    @Test
    @DisplayName("initDataSiVide ne duplique pas les données")
    void pasDeDoublon() {
        long avant = compter("Reservation");
        assertFalse(new DataInitializer(em).initDataSiVide());
        assertEquals(avant, compter("Reservation"));
    }

    private long compter(String entite) {
        return em.createQuery("SELECT COUNT(e) FROM " + entite + " e", Long.class).getSingleResult();
    }

    private long compterStatut(StatutReservation s) {
        return em.createQuery("SELECT COUNT(r) FROM Reservation r WHERE r.statut = :s", Long.class)
                .setParameter("s", s).getSingleResult();
    }
}
