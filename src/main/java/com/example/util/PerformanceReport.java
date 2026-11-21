package com.example.util;

import org.hibernate.SessionFactory;
import org.hibernate.stat.CacheRegionStatistics;
import org.hibernate.stat.QueryStatistics;
import org.hibernate.stat.Statistics;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.logging.Logger;

/**
 * 基于 Hibernate Statistics API 生成性能报告。
 *
 * <p>需要在 persistence.xml 中开启 {@code hibernate.generate_statistics=true}。
 * 统计信息属于 SessionFactory（全局），所有 EntityManager 共享。</p>
 */
public class PerformanceReport {

    private static final Logger LOGGER = Logger.getLogger(PerformanceReport.class.getName());

    private final Statistics stats;

    /** 与 TP 示例保持一致：从 EntityManager 中取出底层的 SessionFactory */
    public PerformanceReport(EntityManager em) {
        this(em.getEntityManagerFactory());
    }

    public PerformanceReport(EntityManagerFactory emf) {
        this.stats = emf.unwrap(SessionFactory.class).getStatistics();
    }

    public Statistics getStatistics() {
        return stats;
    }

    /** 清空统计数据（例如在某个基准测试开始前调用） */
    public void reinitialiser() {
        stats.clear();
    }

    /** 二级缓存命中率（0 ~ 100） */
    public double tauxSucces2ndNiveau() {
        long total = stats.getSecondLevelCacheHitCount() + stats.getSecondLevelCacheMissCount();
        return total == 0 ? 0.0 : 100.0 * stats.getSecondLevelCacheHitCount() / total;
    }

    /** 所有查询的总执行时间（毫秒） */
    public long dureeTotaleRequetesMs() {
        long total = 0;
        for (String hql : stats.getQueries()) {
            QueryStatistics qs = stats.getQueryStatistics(hql);
            total += qs.getExecutionTotalTime();
        }
        return total;
    }

    /** 生成文本格式的报告 */
    public String genererRapport() {
        StringBuilder sb = new StringBuilder();
        String ligne = "=".repeat(64);

        sb.append(ligne).append('\n');
        sb.append("               RAPPORT DE PERFORMANCE HIBERNATE\n");
        sb.append("  Généré le ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")))
                .append('\n');
        sb.append(ligne).append('\n');

        // ---- 会话与事务 ----
        section(sb, "Sessions et transactions");
        valeur(sb, "Sessions ouvertes", stats.getSessionOpenCount());
        valeur(sb, "Transactions", stats.getTransactionCount());
        valeur(sb, "Transactions réussies", stats.getSuccessfulTransactionCount());
        valeur(sb, "Échecs de verrou optimiste", stats.getOptimisticFailureCount());
        valeur(sb, "Requêtes JDBC préparées", stats.getPrepareStatementCount());

        // ---- 查询 ----
        section(sb, "Requêtes JPQL / Criteria");
        valeur(sb, "Requêtes exécutées", stats.getQueryExecutionCount());
        valeur(sb, "Durée totale des requêtes (ms)", dureeTotaleRequetesMs());
        valeur(sb, "Requête la plus lente (ms)", stats.getQueryExecutionMaxTime());
        if (stats.getQueryExecutionMaxTimeQueryString() != null) {
            sb.append("    -> ").append(abreger(stats.getQueryExecutionMaxTimeQueryString(), 90)).append('\n');
        }

        // ---- 实体操作 ----
        section(sb, "Entités");
        valeur(sb, "Chargées (load)", stats.getEntityLoadCount());
        valeur(sb, "Récupérées par SELECT (fetch)", stats.getEntityFetchCount());
        valeur(sb, "Insérées", stats.getEntityInsertCount());
        valeur(sb, "Mises à jour", stats.getEntityUpdateCount());
        valeur(sb, "Supprimées", stats.getEntityDeleteCount());
        valeur(sb, "Collections chargées", stats.getCollectionLoadCount());

        // ---- 二级缓存 ----
        section(sb, "Cache de second niveau");
        valeur(sb, "Hits", stats.getSecondLevelCacheHitCount());
        valeur(sb, "Misses", stats.getSecondLevelCacheMissCount());
        valeur(sb, "Mises à jour (put)", stats.getSecondLevelCachePutCount());
        sb.append(String.format("  %-38s : %.1f %%%n", "Taux de succès", tauxSucces2ndNiveau()));

        // 每个缓存区域的明细
        String[] regions = stats.getSecondLevelCacheRegionNames();
        Arrays.sort(regions);
        for (String region : regions) {
            CacheRegionStatistics rs;
            try {
                rs = stats.getDomainDataRegionStatistics(region);
            } catch (IllegalArgumentException e) {
                // 查询缓存 / 时间戳区域不是“领域数据”区域，单独在下面统计
                continue;
            }
            if (rs != null) {
                sb.append(String.format("    %-36s hits=%-5d misses=%-5d puts=%d%n",
                        abreger(region.replace("com.example.model.", ""), 36),
                        rs.getHitCount(), rs.getMissCount(), rs.getPutCount()));
            }
        }

        // ---- 查询缓存 ----
        section(sb, "Cache de requêtes");
        valeur(sb, "Hits", stats.getQueryCacheHitCount());
        valeur(sb, "Misses", stats.getQueryCacheMissCount());
        valeur(sb, "Mises à jour (put)", stats.getQueryCachePutCount());

        // ---- 最耗时的前 5 条查询 ----
        section(sb, "Top 5 des requêtes (durée totale)");
        Arrays.stream(stats.getQueries())
                .sorted(Comparator.comparingLong((String q) -> stats.getQueryStatistics(q).getExecutionTotalTime())
                        .reversed())
                .limit(5)
                .forEach(q -> {
                    QueryStatistics qs = stats.getQueryStatistics(q);
                    sb.append(String.format("  %4d exec | total %4d ms | moy %3d ms | %s%n",
                            qs.getExecutionCount(), qs.getExecutionTotalTime(), qs.getExecutionAvgTime(),
                            abreger(q, 70)));
                });

        sb.append(ligne).append('\n');
        return sb.toString();
    }

    /** 与 TP 示例保持一致：打印到控制台 */
    public void printReport() {
        System.out.println(genererRapport());
    }

    /** 把报告保存到文件，方便归档和对比 */
    public void ecrireDansFichier(Path fichier) throws IOException {
        if (fichier.getParent() != null) {
            Files.createDirectories(fichier.getParent());
        }
        Files.write(fichier, genererRapport().getBytes(StandardCharsets.UTF_8));
        LOGGER.info("Rapport de performance écrit dans " + fichier.toAbsolutePath());
    }

    private static void section(StringBuilder sb, String titre) {
        sb.append('\n').append("-- ").append(titre).append(' ')
                .append("-".repeat(Math.max(0, 58 - titre.length()))).append('\n');
    }

    private static void valeur(StringBuilder sb, String libelle, long valeur) {
        sb.append(String.format("  %-38s : %d%n", libelle, valeur));
    }

    private static String abreger(String texte, int max) {
        String t = texte.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max - 3) + "...";
    }
}
