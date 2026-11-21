package com.example.test;

import com.example.model.Equipement;
import com.example.model.Reservation;
import com.example.model.Salle;
import com.example.repository.ReservationRepository;
import com.example.util.TransactionHelper;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 二级缓存与查询缓存测试：通过 Hibernate 统计信息判断数据来自缓存还是数据库。
 */
class CacheTest extends AbstractJpaTest {

    private Statistics stats;
    private Long idSalle;

    @BeforeEach
    void preparer() {
        idSalle = salle("Salle A").getId();
        emf.getCache().evictAll();   // 从“冷缓存”开始
        stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.clear();
    }

    @Test
    @DisplayName("Le 2e EntityManager lit la salle depuis le cache L2, sans SQL")
    void entiteDepuisLeCache() {
        TransactionHelper.lire(emf, e -> e.find(Salle.class, idSalle));
        assertEquals(1, stats.getSecondLevelCacheMissCount(), "1er accès : absent du cache");
        assertEquals(1, stats.getSecondLevelCachePutCount(), "1er accès : mis en cache");
        long sqlAvant = stats.getPrepareStatementCount();

        TransactionHelper.lire(emf, e -> e.find(Salle.class, idSalle));

        assertEquals(1, stats.getSecondLevelCacheHitCount(), "2e accès : trouvé dans le cache");
        assertEquals(sqlAvant, stats.getPrepareStatementCount(), "Aucune requête SQL supplémentaire");
        assertTrue(emf.getCache().contains(Salle.class, idSalle));
    }

    @Test
    @DisplayName("Le cache de premier niveau évite même l'accès au cache L2")
    void cachePremierNiveau() {
        TransactionHelper.lire(emf, e -> {
            Salle s1 = e.find(Salle.class, idSalle);
            Salle s2 = e.find(Salle.class, idSalle);
            assertTrue(s1 == s2, "Même instance dans le même EntityManager");
            return null;
        });
        assertEquals(0, stats.getSecondLevelCacheHitCount());
    }

    @Test
    @DisplayName("READ_WRITE : une modification est visible par les EntityManager suivants")
    void coherenceReadWrite() {
        TransactionHelper.lire(emf, e -> e.find(Salle.class, idSalle)); // 放入缓存

        TransactionHelper.executerSansResultat(emf, e -> e.find(Salle.class, idSalle).setCapacite(25));

        int capacite = TransactionHelper.lire(emf, e -> e.find(Salle.class, idSalle).getCapacite());
        assertEquals(25, capacite);
        assertTrue(stats.getSecondLevelCacheHitCount() >= 1, "Relu depuis le cache mis à jour");
    }

    @Test
    @DisplayName("Collection equipements mise en cache (region Salle.equipements)")
    void cacheDeCollection() {
        int nb1 = TransactionHelper.lire(emf, e -> e.find(Salle.class, idSalle).getEquipements().size());
        long chargementsCollections = stats.getCollectionLoadCount();

        int nb2 = TransactionHelper.lire(emf, e -> e.find(Salle.class, idSalle).getEquipements().size());

        assertEquals(nb1, nb2);
        assertEquals(chargementsCollections, stats.getCollectionLoadCount(), "Collection non rechargée depuis la base");
        assertTrue(emf.getCache().contains(Equipement.class,
                TransactionHelper.lire(emf, e -> e.find(Salle.class, idSalle).getEquipements().iterator().next().getId())));
    }

    @Test
    @DisplayName("Cache de requêtes : la 2e exécution est servie par le cache")
    void cacheDeRequetes() {
        TransactionHelper.lire(emf, e -> new ReservationRepository(e).findToutesLesSallesEnCache());
        assertEquals(1, stats.getQueryCacheMissCount());
        assertEquals(1, stats.getQueryCachePutCount());

        int taille = TransactionHelper.lire(emf, e -> new ReservationRepository(e).findToutesLesSallesEnCache().size());

        assertEquals(1, stats.getQueryCacheHitCount());
        assertEquals(6, taille);
    }

    @Test
    @DisplayName("Le cache de requêtes est invalidé quand la table change")
    void invalidationCacheRequetes() {
        TransactionHelper.lire(emf, e -> new ReservationRepository(e).findToutesLesSallesEnCache());
        TransactionHelper.executerSansResultat(emf, e -> e.persist(new Salle("Salle Nouvelle", 10, "Annexe", 0)));

        int taille = TransactionHelper.lire(emf, e -> new ReservationRepository(e).findToutesLesSallesEnCache().size());

        assertEquals(7, taille, "La nouvelle salle doit apparaître");
        assertEquals(0, stats.getQueryCacheHitCount());
    }

    @Test
    @DisplayName("Reservation n'est pas @Cacheable : jamais dans le cache L2")
    void reservationNonCachee() {
        Long idRes = TransactionHelper.lire(emf, e ->
                e.createQuery("SELECT r.id FROM Reservation r", Long.class).setMaxResults(1).getSingleResult());
        TransactionHelper.lire(emf, e -> e.find(Reservation.class, idRes));
        assertFalse(emf.getCache().contains(Reservation.class, idRes));
    }
}
