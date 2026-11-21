package com.example.test;

import com.example.model.Reservation;
import com.example.model.Salle;
import com.example.model.StatutReservation;
import com.example.model.Utilisateur;
import com.example.service.ConflitReservationException;
import com.example.service.ModificationConcurrenteException;
import com.example.service.ReservationException;
import com.example.service.ReservationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.persistence.EntityManager;
import javax.persistence.OptimisticLockException;
import javax.persistence.PersistenceException;
import javax.persistence.RollbackException;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 预订功能测试：增删改查、冲突检测、业务校验、乐观锁。
 */
class ReservationTest extends AbstractJpaTest {

    private ReservationService service;
    private Long salleA;
    private Long salleB;
    private Long alice;
    private Long bruno;

    /** 一个肯定空闲的时间段：60 天后（初始数据只覆盖未来两周） */
    private final LocalDateTime debut = LocalDate.now().plusDays(60).atTime(9, 0);
    private final LocalDateTime fin = debut.plusHours(2);

    @BeforeEach
    void preparer() {
        service = new ReservationService(emf);
        salleA = salle("Salle A").getId();
        salleB = salle("Salle B").getId();
        alice = utilisateur("alice.dupont@example.com").getId();
        bruno = utilisateur("bruno.martin@example.com").getId();
    }

    // ------------------------------------------------------------------
    // 正常场景
    // ------------------------------------------------------------------

    @Test
    @DisplayName("TP : création d'une réservation directement avec l'EntityManager")
    void testCreateReservation() {
        em.getTransaction().begin();

        Utilisateur user = new Utilisateur("Test User", "test.user@example.com", "QA");
        em.persist(user);

        Salle salle = em.find(Salle.class, salleA);

        Reservation reservation = new Reservation();
        reservation.setUtilisateur(user);
        reservation.setSalle(salle);
        reservation.setDateDebut(LocalDateTime.now().plusDays(2));
        reservation.setDateFin(LocalDateTime.now().plusDays(2).plusHours(1));

        em.persist(reservation);
        em.getTransaction().commit();

        assertNotNull(reservation.getId());
        assertNotNull(reservation.getDateCreation(), "@PrePersist doit renseigner la date de création");
        assertEquals(StatutReservation.CONFIRMEE, reservation.getStatut());
    }

    @Test
    @DisplayName("Création via le service puis relecture avec les associations")
    void creationEtLecture() {
        Reservation r = service.creerReservation(alice, salleA, debut, fin, "Réunion");

        Reservation relue = service.trouverAvecDetails(r.getId());
        assertNotNull(relue);
        // EntityManager 已关闭，但 JOIN FETCH 已加载关联，不会抛出 LazyInitializationException
        assertEquals("Salle A", relue.getSalle().getNom());
        assertEquals("alice.dupont@example.com", relue.getUtilisateur().getEmail());
        assertEquals(0, relue.getVersion());
    }

    @Test
    @DisplayName("Deux créneaux adjacents (fin = début) ne sont pas en conflit")
    void creneauxAdjacents() {
        service.creerReservation(alice, salleA, debut, fin, "Premier");
        Reservation suivant = service.creerReservation(bruno, salleA, fin, fin.plusHours(1), "Suivant");
        assertNotNull(suivant.getId());
    }

    @Test
    @DisplayName("Même créneau dans une autre salle : autorisé")
    void memeCreneauAutreSalle() {
        service.creerReservation(alice, salleA, debut, fin, "Salle A");
        assertNotNull(service.creerReservation(bruno, salleB, debut, fin, "Salle B").getId());
    }

    @Test
    @DisplayName("Modification du créneau : la version est incrémentée")
    void modificationCreneau() {
        Reservation r = service.creerReservation(alice, salleA, debut, fin, "À déplacer");

        Reservation modifiee = service.modifierCreneau(r.getId(), debut.plusDays(1), fin.plusDays(1), r.getVersion());

        assertEquals(debut.plusDays(1), modifiee.getDateDebut());
        assertEquals(1, service.trouverAvecDetails(r.getId()).getVersion());
    }

    @Test
    @DisplayName("Annulation : le créneau redevient disponible")
    void annulationLibereLeCreneau() {
        Reservation r = service.creerReservation(alice, salleA, debut, fin, "Sera annulée");
        service.annulerReservation(r.getId());

        assertEquals(StatutReservation.ANNULEE, service.trouverAvecDetails(r.getId()).getStatut());
        assertNotNull(service.creerReservation(bruno, salleA, debut, fin, "Reprise du créneau").getId());
    }

    @Test
    @DisplayName("Suppression d'une réservation")
    void suppression() {
        Reservation r = service.creerReservation(alice, salleA, debut, fin, "À supprimer");
        service.supprimerReservation(r.getId());
        assertNull(service.trouverAvecDetails(r.getId()));
    }

    // ------------------------------------------------------------------
    // 错误场景
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Conflit : chevauchement partiel dans la même salle")
    void conflitChevauchement() {
        service.creerReservation(alice, salleA, debut, fin, "Original");

        ConflitReservationException ex = assertThrows(ConflitReservationException.class,
                () -> service.creerReservation(bruno, salleA, debut.plusMinutes(30), fin.plusHours(1), "Conflit"));
        assertTrue(ex.getMessage().contains("Salle A"));
    }

    @Test
    @DisplayName("Conflit : créneau englobé dans une réservation existante")
    void conflitInclusion() {
        service.creerReservation(alice, salleA, debut, fin, "Original");
        assertThrows(ConflitReservationException.class,
                () -> service.creerReservation(bruno, salleA, debut.plusMinutes(15), fin.minusMinutes(15), "Inclus"));
    }

    @Test
    @DisplayName("Conflit lors de la modification d'un créneau")
    void conflitModification() {
        service.creerReservation(alice, salleA, debut, fin, "Fixe");
        Reservation autre = service.creerReservation(bruno, salleA, fin, fin.plusHours(1), "Mobile");

        assertThrows(ConflitReservationException.class,
                () -> service.modifierCreneau(autre.getId(), debut, fin, autre.getVersion()));
    }

    @Test
    @DisplayName("Dates invalides : fin avant début, ou réservation dans le passé")
    void datesInvalides() {
        assertThrows(ReservationException.class, () -> service.creerReservation(alice, salleA, fin, debut, "Inversé"));
        assertThrows(ReservationException.class, () -> service.creerReservation(alice, salleA, debut, debut, "Durée 0"));
        assertThrows(ReservationException.class, () -> service.creerReservation(alice, salleA,
                LocalDateTime.now().minusDays(1), LocalDateTime.now().minusDays(1).plusHours(1), "Passé"));
    }

    @Test
    @DisplayName("Salle ou utilisateur inexistant")
    void entitesInexistantes() {
        assertThrows(ReservationException.class, () -> service.creerReservation(alice, -1L, debut, fin, "?"));
        assertThrows(ReservationException.class, () -> service.creerReservation(-1L, salleA, debut, fin, "?"));
    }

    @Test
    @DisplayName("Contrainte d'unicité sur l'email (index créé par la migration v2.0)")
    void emailUnique() {
        em.getTransaction().begin();
        // IDENTITY 主键：persist 时立即执行 INSERT，因此异常在 persist/flush 时抛出
        assertThrows(PersistenceException.class, () -> {
            em.persist(new Utilisateur("Doublon", "alice.dupont@example.com", null));
            em.flush();
        });
    }

    // ------------------------------------------------------------------
    // 乐观锁
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Verrou optimiste : deux EntityManager modifient la même réservation")
    void verrouOptimisteJpaBrut() {
        Long id = service.creerReservation(alice, salleA, debut, fin, "Concurrence").getId();

        EntityManager em1 = emf.createEntityManager();
        EntityManager em2 = emf.createEntityManager();
        try {
            em1.getTransaction().begin();
            em2.getTransaction().begin();
            Reservation r1 = em1.find(Reservation.class, id);
            Reservation r2 = em2.find(Reservation.class, id); // 两者读取到同一个版本 0

            r1.setMotif("Modifié par EM1");
            em1.getTransaction().commit(); // 版本变为 1

            r2.setMotif("Modifié par EM2");
            // UPDATE ... WHERE id=? AND version=0 影响 0 行 → 乐观锁异常
            RollbackException ex = assertThrows(RollbackException.class, () -> em2.getTransaction().commit());
            assertInstanceOf(OptimisticLockException.class, ex.getCause());
        } finally {
            em1.close();
            em2.close();
        }
        assertEquals("Modifié par EM1", service.trouverAvecDetails(id).getMotif());
    }

    @Test
    @DisplayName("Verrou optimiste via le service : objet détaché périmé")
    void verrouOptimisteService() {
        Long id = service.creerReservation(alice, salleA, debut, fin, "Concurrence").getId();
        Reservation copie1 = service.trouverAvecDetails(id);
        Reservation copie2 = service.trouverAvecDetails(id);

        copie1.setMotif("Version de l'utilisateur 1");
        service.mettreAJour(copie1);

        copie2.setMotif("Version de l'utilisateur 2");
        ModificationConcurrenteException ex = assertThrows(ModificationConcurrenteException.class,
                () -> service.mettreAJour(copie2));
        assertTrue(ex.getMessage().contains("réessayer"));
    }

    @Test
    @DisplayName("Verrou optimiste : version connue périmée lors du déplacement")
    void versionPerimee() {
        Reservation r = service.creerReservation(alice, salleA, debut, fin, "v0");
        service.modifierCreneau(r.getId(), debut.plusHours(3), fin.plusHours(3), 0); // → v1

        assertThrows(ModificationConcurrenteException.class,
                () -> service.modifierCreneau(r.getId(), debut.plusHours(5), fin.plusHours(5), 0));
    }
}
