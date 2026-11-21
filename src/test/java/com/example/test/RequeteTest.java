package com.example.test;

import com.example.model.Reservation;
import com.example.model.Salle;
import com.example.model.StatutReservation;
import com.example.repository.CritereRecherche;
import com.example.repository.ReservationRepository;
import com.example.repository.StatistiqueSalle;
import com.example.service.ReservationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JPQL 与 Criteria API 查询测试。
 */
class RequeteTest extends AbstractJpaTest {

    private ReservationRepository repo;

    @BeforeEach
    void preparer() {
        repo = new ReservationRepository(em);
    }

    @Test
    @DisplayName("JPQL et Criteria API renvoient exactement les mêmes réservations")
    void jpqlEgalCriteria() {
        LocalDateTime maintenant = LocalDateTime.now();
        List<Long> jpql = ids(repo.findBySalleEtDateApres("Salle A", maintenant));
        List<Long> criteria = ids(repo.findBySalleEtDateApresCriteria("Salle A", maintenant));

        assertFalse(jpql.isEmpty(), "Le jeu de données contient des réservations futures pour la Salle A");
        assertEquals(jpql, criteria);
    }

    @Test
    @DisplayName("Paramètres liés : une tentative d'injection ne renvoie rien")
    void injectionImpossible() {
        assertTrue(repo.findBySalleEtDateApres("Salle A' OR '1'='1", LocalDateTime.now().minusYears(10)).isEmpty());
    }

    @Test
    @DisplayName("Criteria dynamique : sans critère = toutes les réservations")
    void rechercheSansCritere() {
        long total = em.createQuery("SELECT COUNT(r) FROM Reservation r", Long.class).getSingleResult();
        assertEquals(total, repo.rechercher(new CritereRecherche()).size());
    }

    @Test
    @DisplayName("Criteria dynamique : combinaison de critères")
    void rechercheCombinee() {
        LocalDateTime maintenant = LocalDateTime.now();
        List<Reservation> res = repo.rechercher(new CritereRecherche()
                .utilisateur("alice.dupont@example.com")
                .statut(StatutReservation.CONFIRMEE)
                .debutApres(maintenant));

        assertFalse(res.isEmpty());
        for (Reservation r : res) {
            // 关联已通过 fetch join 加载
            assertEquals("alice.dupont@example.com", r.getUtilisateur().getEmail());
            assertEquals(StatutReservation.CONFIRMEE, r.getStatut());
            assertTrue(r.getDateDebut().isAfter(maintenant));
        }
    }

    @Test
    @DisplayName("Criteria dynamique : capacité minimale")
    void rechercheParCapacite() {
        List<Reservation> res = repo.rechercher(new CritereRecherche().capaciteMin(30));
        assertFalse(res.isEmpty());
        assertTrue(res.stream().allMatch(r -> r.getSalle().getCapacite() >= 30));
    }

    @Test
    @DisplayName("Salles disponibles : une salle réservée disparaît de la liste")
    void sallesDisponibles() {
        LocalDateTime debut = LocalDate.now().plusDays(90).atTime(10, 0);
        LocalDateTime fin = debut.plusHours(1);
        assertEquals(6, repo.findSallesDisponibles(debut, fin, 1).size());

        Long salleA = salle("Salle A").getId();
        Long alice = utilisateur("alice.dupont@example.com").getId();
        new ReservationService(emf).creerReservation(alice, salleA, debut, fin, "Occupation");

        List<String> libres = repo.findSallesDisponibles(debut, fin, 1).stream()
                .map(Salle::getNom).collect(Collectors.toList());
        assertEquals(5, libres.size());
        assertFalse(libres.contains("Salle A"));
        // 容量过滤
        assertTrue(repo.findSallesDisponibles(debut, fin, 100).stream().allMatch(s -> s.getCapacite() >= 100));
    }

    @Test
    @DisplayName("Statistiques par salle (SELECT NEW) cohérentes avec le nombre total")
    void statistiques() {
        List<StatistiqueSalle> stats = repo.statistiquesParSalle();
        long totalNonAnnulees = em.createQuery(
                        "SELECT COUNT(r) FROM Reservation r WHERE r.statut <> :a", Long.class)
                .setParameter("a", StatutReservation.ANNULEE).getSingleResult();

        assertEquals(6, stats.size(), "Une ligne par salle, même sans réservation (LEFT JOIN)");
        assertEquals(totalNonAnnulees, stats.stream().mapToLong(StatistiqueSalle::getNombreReservations).sum());
        // 按预订数量降序排列
        for (int i = 1; i < stats.size(); i++) {
            assertTrue(stats.get(i - 1).getNombreReservations() >= stats.get(i).getNombreReservations());
        }
    }

    private static List<Long> ids(List<Reservation> liste) {
        return liste.stream().map(Reservation::getId).collect(Collectors.toList());
    }
}
