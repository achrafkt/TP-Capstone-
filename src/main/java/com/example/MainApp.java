package com.example;

import com.example.config.DatabaseConfig;
import com.example.config.LogConfig;
import com.example.model.Reservation;
import com.example.model.Salle;
import com.example.model.StatutReservation;
import com.example.model.Utilisateur;
import com.example.repository.CritereRecherche;
import com.example.repository.ReservationRepository;
import com.example.service.ConflitReservationException;
import com.example.service.ModificationConcurrenteException;
import com.example.service.ReservationException;
import com.example.service.ReservationService;
import com.example.util.DataInitializer;
import com.example.util.JpaUtil;
import com.example.util.MigrationExecutor;
import com.example.util.PerformanceReport;
import com.example.util.TransactionHelper;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 主程序：把各个部分串联起来。
 *
 * <ol>
 *   <li>执行数据库迁移（JDBC）；</li>
 *   <li>创建 EntityManagerFactory（Hibernate 校验表结构）；</li>
 *   <li>初始化测试数据；</li>
 *   <li>演示二级缓存、业务规则、冲突检测、乐观锁、JPQL 与 Criteria 查询；</li>
 *   <li>输出性能报告。</li>
 * </ol>
 */
public class MainApp {

    private static final Logger LOGGER = Logger.getLogger(MainApp.class.getName());

    public static void main(String[] args) {
        // 控制台统一使用 UTF-8 输出，保证法语重音字符正常显示（Windows 下可先执行 chcp 65001）
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        LogConfig.initialiser();
        DatabaseConfig config = DatabaseConfig.depuisEnvironnement();
        LOGGER.info("Base de données : " + config.getJdbcUrl());

        // 1) 数据库迁移：必须在 Hibernate 启动之前执行，否则 validate 会失败
        try {
            MigrationExecutor migrationExecutor = new MigrationExecutor(
                    config.getJdbcUrl(), config.getUsername(), config.getPassword());
            migrationExecutor.executerToutesLesMigrations();
        } catch (MigrationExecutor.MigrationException e) {
            LOGGER.log(Level.SEVERE, "Migration impossible, arrêt de l'application", e);
            System.exit(1);
            return;
        }

        EntityManagerFactory emf = null;
        try {
            emf = JpaUtil.creerEntityManagerFactory(config);

            // 2) 初始化数据
            EntityManager em = emf.createEntityManager();
            try {
                new DataInitializer(em).initDataSiVide();
            } finally {
                em.close();
            }

            // 统计从这里开始，报告只反映演示部分
            PerformanceReport report = new PerformanceReport(emf);
            report.reinitialiser();

            demoCache(emf);
            demoReservations(emf);
            demoRequetes(emf);

            // 3) 性能报告
            report.printReport();
            report.ecrireDansFichier(Paths.get("rapports", "rapport-performance.txt"));
        } catch (Exception e) {
            // 任何未处理的异常都必须记录日志
            LOGGER.log(Level.SEVERE, "Erreur inattendue pendant l'exécution", e);
        } finally {
            // 始终释放资源
            if (emf != null && emf.isOpen()) {
                emf.close();
            }
        }
    }

    // ------------------------------------------------------------------
    // 演示：二级缓存
    // ------------------------------------------------------------------

    private static void demoCache(EntityManagerFactory emf) {
        titre("Cache de second niveau");
        Long idSalle = TransactionHelper.lire(emf, em ->
                em.createQuery("SELECT s.id FROM Salle s WHERE s.nom = 'Salle A'", Long.class).getSingleResult());

        // 第一个 EntityManager：从数据库加载（缓存未命中 → 写入缓存）
        TransactionHelper.lire(emf, em -> {
            Salle s = em.find(Salle.class, idSalle);
            System.out.println("EM n°1 : " + s + " -> " + s.getEquipements());
            return s;
        });
        // 第二个 EntityManager：一级缓存是空的，但二级缓存命中 → 控制台不会出现 SELECT
        TransactionHelper.lire(emf, em -> {
            Salle s = em.find(Salle.class, idSalle);
            System.out.println("EM n°2 (depuis le cache L2, aucun SELECT) : " + s + " -> " + s.getEquipements());
            return s;
        });
        // 查询缓存：第二次执行相同查询不会访问数据库
        for (int i = 1; i <= 2; i++) {
            int n = i;
            TransactionHelper.lire(emf, em -> {
                List<Salle> salles = new ReservationRepository(em).findToutesLesSallesEnCache();
                System.out.println("Requête en cache, exécution n°" + n + " : " + salles.size() + " salles");
                return salles;
            });
        }
    }

    // ------------------------------------------------------------------
    // 演示：业务服务、冲突检测、乐观锁
    // ------------------------------------------------------------------

    private static void demoReservations(EntityManagerFactory emf) {
        titre("Service de réservation");
        ReservationService service = new ReservationService(emf);

        Long[] ids = TransactionHelper.lire(emf, em -> new Long[]{
                em.createNamedQuery("Utilisateur.findByEmail", Utilisateur.class)
                        .setParameter("email", "bruno.martin@example.com").getSingleResult().getId(),
                em.createNamedQuery("Utilisateur.findByEmail", Utilisateur.class)
                        .setParameter("email", "chloe.bernard@example.com").getSingleResult().getId(),
                em.createQuery("SELECT s.id FROM Salle s WHERE s.nom = 'Salle A'", Long.class).getSingleResult()});
        Long bruno = ids[0];
        Long chloe = ids[1];
        Long salleA = ids[2];

        // 找一个空闲时间段（保证程序可以重复运行）
        LocalDateTime debut = premierCreneauLibre(emf, salleA);
        LocalDateTime fin = debut.plusHours(2);

        // 正常创建
        Reservation r = service.creerReservation(bruno, salleA, debut, fin, "Démo MainApp");
        System.out.println("Créée : " + r);

        // 冲突：同一会议室、重叠时间段
        try {
            service.creerReservation(chloe, salleA, debut.plusHours(1), fin.plusHours(1), "Conflit volontaire");
        } catch (ConflitReservationException e) {
            System.out.println("Conflit attendu : " + e.getMessage());
        }

        // 非法时间段
        try {
            service.creerReservation(chloe, salleA, fin, debut, "Dates inversées");
        } catch (ReservationException e) {
            System.out.println("Erreur de validation attendue : " + e.getMessage());
        }

        // 乐观锁：两个用户读取同一预订（同一版本），先后修改
        Reservation copieBruno = service.trouverAvecDetails(r.getId());
        Reservation copieChloe = service.trouverAvecDetails(r.getId());

        copieBruno.setMotif("Démo MainApp (modifiée par Bruno)");
        service.mettreAJour(copieBruno);
        System.out.println("Bruno a modifié la réservation : version " + copieBruno.getVersion() + " -> "
                + service.trouverAvecDetails(r.getId()).getVersion());

        copieChloe.setMotif("Démo MainApp (modifiée par Chloé)");
        try {
            service.mettreAJour(copieChloe); // 版本号已过期
        } catch (ModificationConcurrenteException e) {
            System.out.println("Verrou optimiste : " + e.getMessage());
        }

        // 取消：释放会议室
        service.annulerReservation(r.getId());
        System.out.println("Statut final : " + service.trouverAvecDetails(r.getId()).getStatut());
    }

    private static LocalDateTime premierCreneauLibre(EntityManagerFactory emf, Long salleId) {
        return TransactionHelper.lire(emf, em -> {
            ReservationRepository repo = new ReservationRepository(em);
            LocalDateTime candidat = LocalDate.now().plusDays(30).atTime(9, 0);
            while (repo.existeConflit(salleId, candidat, candidat.plusHours(3), null)) {
                candidat = candidat.plusDays(1);
            }
            return candidat;
        });
    }

    // ------------------------------------------------------------------
    // 演示：JPQL 与 Criteria API
    // ------------------------------------------------------------------

    private static void demoRequetes(EntityManagerFactory emf) {
        titre("Requêtes JPQL et Criteria API");
        TransactionHelper.lire(emf, em -> {
            ReservationRepository repo = new ReservationRepository(em);
            LocalDateTime maintenant = LocalDateTime.now();

            List<Reservation> jpql = repo.findBySalleEtDateApres("Salle A", maintenant);
            List<Reservation> criteria = repo.findBySalleEtDateApresCriteria("Salle A", maintenant);
            System.out.println("Réservations futures de la Salle A : JPQL=" + jpql.size()
                    + ", Criteria=" + criteria.size());

            List<Reservation> grandes = repo.rechercher(new CritereRecherche()
                    .capaciteMin(20).statut(StatutReservation.CONFIRMEE).debutApres(maintenant)
                    .debutAvant(maintenant.plusDays(7)));
            System.out.println("Recherche dynamique (capacité >= 20, confirmées, 7 prochains jours) : "
                    + grandes.size() + " résultat(s)");
            grandes.stream().limit(5).forEach(r -> System.out.println("   " + r.getSalle().getNom() + " | "
                    + r.getDateDebut() + " | " + r.getUtilisateur().getNom() + " | " + r.getMotif()));

            LocalDateTime demain10h = LocalDate.now().plusDays(1).atTime(10, 30);
            List<Salle> libres = repo.findSallesDisponibles(demain10h, demain10h.plusHours(1), 6);
            System.out.println("Salles libres demain 10h30-11h30 (>= 6 places) : " + libres);

            System.out.println("Statistiques par salle :");
            repo.statistiquesParSalle().forEach(s -> System.out.println("   " + s));
            return null;
        });
    }

    private static void titre(String texte) {
        System.out.println();
        System.out.println("################ " + texte + " ################");
    }
}
