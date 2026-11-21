package com.example.test;

import com.example.model.Salle;
import com.example.repository.ReservationRepository;
import com.example.util.PerformanceReport;
import com.example.util.TransactionHelper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 性能报告测试。
 */
class PerformanceReportTest extends AbstractJpaTest {

    @Test
    @DisplayName("Le rapport reflète l'activité : requêtes et hits du cache")
    void rapportApresActivite() {
        PerformanceReport report = new PerformanceReport(em);
        Long idSalle = salle("Salle A").getId();
        emf.getCache().evictAll();
        report.reinitialiser();

        for (int i = 0; i < 5; i++) {
            TransactionHelper.lire(emf, e -> e.find(Salle.class, idSalle)); // 1 miss + 4 hits
        }
        TransactionHelper.lire(emf, e -> new ReservationRepository(e)
                .findBySalleEtDateApres("Salle A", LocalDateTime.now()));

        assertEquals(4, report.getStatistics().getSecondLevelCacheHitCount());
        assertEquals(80.0, report.tauxSucces2ndNiveau(), 0.001);
        assertEquals(1, report.getStatistics().getQueryExecutionCount());

        String texte = report.genererRapport();
        assertTrue(texte.contains("RAPPORT DE PERFORMANCE"));
        assertTrue(texte.contains("Cache de second niveau"));
        assertTrue(texte.contains("80.0 %") || texte.contains("80,0 %"));
        assertTrue(texte.contains("Salle"), "Détail par région de cache");
    }

    @Test
    @DisplayName("Le rapport peut être écrit dans un fichier")
    void ecritureFichier(@TempDir Path dossier) throws Exception {
        Path fichier = dossier.resolve("sous-dossier/rapport.txt");
        new PerformanceReport(emf).ecrireDansFichier(fichier);

        assertTrue(Files.exists(fichier));
        assertTrue(new String(Files.readAllBytes(fichier), StandardCharsets.UTF_8).contains("Transactions"));
    }

    @AfterAll
    static void afficher() {
        // 在测试日志中打印一份完整报告，方便查看
        if (emf != null && emf.isOpen()) {
            new PerformanceReport(emf).printReport();
        }
    }
}
