package com.example.util;

import com.example.model.Equipement;
import com.example.model.Reservation;
import com.example.model.Salle;
import com.example.model.StatutReservation;
import com.example.model.Utilisateur;

import javax.persistence.EntityManager;
import javax.persistence.EntityTransaction;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 生成一套接近真实场景的测试数据：用户、会议室、设备和预订。
 *
 * <ul>
 *   <li>日期是动态的（相对于“今天”），因此每次运行预订都有效；</li>
 *   <li>同一会议室同一天使用固定且互不重叠的时间段，保证数据本身没有冲突；</li>
 *   <li>使用固定随机种子，保证每次生成的数据可复现；</li>
 *   <li>所有插入在同一个事务中完成，失败则整体回滚。</li>
 * </ul>
 */
public class DataInitializer {

    private static final Logger LOGGER = Logger.getLogger(DataInitializer.class.getName());

    // 每天可预订的时间段（彼此不重叠）
    private static final LocalTime[][] CRENEAUX = {
            {LocalTime.of(8, 30), LocalTime.of(10, 0)},
            {LocalTime.of(10, 30), LocalTime.of(12, 0)},
            {LocalTime.of(14, 0), LocalTime.of(15, 30)},
            {LocalTime.of(16, 0), LocalTime.of(18, 0)},
    };

    private static final String[] MOTIFS = {
            "Réunion d'équipe hebdomadaire", "Point projet client", "Entretien de recrutement",
            "Formation JPA / Hibernate", "Revue de sprint", "Comité de direction",
            "Atelier de conception", "Démonstration produit", "Séminaire interne",
    };

    private final EntityManager em;

    public DataInitializer(EntityManager em) {
        this.em = em;
    }

    /** 仅当数据库为空时才初始化（让主程序可重复运行） */
    public boolean initDataSiVide() {
        long nbSalles = em.createQuery("SELECT COUNT(s) FROM Salle s", Long.class).getSingleResult();
        if (nbSalles > 0) {
            LOGGER.info("Données déjà présentes (" + nbSalles + " salles), initialisation ignorée");
            return false;
        }
        initData();
        return true;
    }

    public void initData() {
        EntityTransaction tx = em.getTransaction();
        try {
            tx.begin();

            List<Equipement> equipements = creerEquipements();
            List<Utilisateur> utilisateurs = creerUtilisateurs();
            List<Salle> salles = creerSalles(equipements);
            int nbReservations = creerReservations(utilisateurs, salles);

            tx.commit();
            LOGGER.info(String.format("Jeu de données créé : %d utilisateurs, %d salles, %d équipements, %d réservations",
                    utilisateurs.size(), salles.size(), equipements.size(), nbReservations));
        } catch (RuntimeException e) {
            if (tx.isActive()) {
                tx.rollback();
            }
            LOGGER.log(Level.SEVERE, "Échec de l'initialisation des données", e);
            throw e;
        }
    }

    private List<Equipement> creerEquipements() {
        List<Equipement> liste = new ArrayList<>();
        for (String nom : new String[]{"Vidéoprojecteur", "Tableau blanc", "Visioconférence",
                "Écran interactif", "Système audio", "Paperboard"}) {
            Equipement e = new Equipement(nom);
            em.persist(e);
            liste.add(e);
        }
        return liste;
    }

    private List<Utilisateur> creerUtilisateurs() {
        String[][] donnees = {
                {"Alice Dupont", "alice.dupont@example.com", "Ressources humaines"},
                {"Bruno Martin", "bruno.martin@example.com", "Informatique"},
                {"Chloé Bernard", "chloe.bernard@example.com", "Marketing"},
                {"David Petit", "david.petit@example.com", "Finance"},
                {"Emma Robert", "emma.robert@example.com", "Informatique"},
                {"Farid Haddad", "farid.haddad@example.com", "Direction"},
                {"Inès Moreau", "ines.moreau@example.com", "Commercial"},
                {"Li Wei", "li.wei@example.com", "Recherche et développement"},
        };
        List<Utilisateur> liste = new ArrayList<>();
        for (String[] d : donnees) {
            Utilisateur u = new Utilisateur(d[0], d[1], d[2]);
            em.persist(u);
            liste.add(u);
        }
        return liste;
    }

    private List<Salle> creerSalles(List<Equipement> eq) {
        List<Salle> liste = new ArrayList<>();
        liste.add(salle("Salle A", 20, "Bâtiment Nord", 1, "Salle de réunion polyvalente", eq.get(0), eq.get(1)));
        liste.add(salle("Salle B", 8, "Bâtiment Nord", 1, "Petite salle pour points rapides", eq.get(1)));
        liste.add(salle("Salle Conseil", 30, "Bâtiment Nord", 3, "Salle du comité de direction",
                eq.get(0), eq.get(2), eq.get(4)));
        liste.add(salle("Salle Innovation", 12, "Bâtiment Sud", 2, "Espace d'ateliers créatifs",
                eq.get(3), eq.get(5), eq.get(1)));
        liste.add(salle("Amphithéâtre", 120, "Bâtiment Sud", 0, "Conférences et formations",
                eq.get(0), eq.get(4), eq.get(2)));
        liste.add(salle("Salle Focus", 4, "Bâtiment Sud", 2, "Bulle pour appels et entretiens", eq.get(2)));
        return liste;
    }

    private Salle salle(String nom, int capacite, String batiment, int etage, String description,
                        Equipement... equipements) {
        Salle s = new Salle(nom, capacite, batiment, etage);
        s.setDescription(description);
        for (Equipement e : equipements) {
            s.ajouterEquipement(e);
        }
        em.persist(s);
        return s;
    }

    /**
     * 为接下来 10 个工作日生成预订，另加一些过去（已结束）和已取消的预订。
     */
    private int creerReservations(List<Utilisateur> utilisateurs, List<Salle> salles) {
        Random random = new Random(42); // 固定种子：数据可复现
        int compteur = 0;

        // 1) 示例中的固定预订：Alice 明天 10:30 在 Salle A
        LocalDate demain = LocalDate.now().plusDays(1);
        compteur += persister(utilisateurs.get(0), salles.get(0),
                demain.atTime(CRENEAUX[1][0]), demain.atTime(CRENEAUX[1][1]),
                "Réunion d'équipe hebdomadaire", StatutReservation.CONFIRMEE);

        // 2) 未来的预订：每个工作日、每个会议室随机占用部分时间段
        LocalDate jour = LocalDate.now().plusDays(1);
        int joursOuvres = 0;
        while (joursOuvres < 10) {
            if (jour.getDayOfWeek() != DayOfWeek.SATURDAY && jour.getDayOfWeek() != DayOfWeek.SUNDAY) {
                for (Salle salle : salles) {
                    for (int c = 0; c < CRENEAUX.length; c++) {
                        // 明天 Salle A 的第 2 个时间段已被上面的固定预订占用
                        if (jour.equals(demain) && salle == salles.get(0) && c == 1) {
                            continue;
                        }
                        if (random.nextInt(100) < 35) { // 约 35% 的占用率
                            Utilisateur u = utilisateurs.get(random.nextInt(utilisateurs.size()));
                            StatutReservation statut = random.nextInt(100) < 8
                                    ? StatutReservation.ANNULEE : StatutReservation.CONFIRMEE;
                            compteur += persister(u, salle, jour.atTime(CRENEAUX[c][0]), jour.atTime(CRENEAUX[c][1]),
                                    MOTIFS[random.nextInt(MOTIFS.length)], statut);
                        }
                    }
                }
                joursOuvres++;
            }
            jour = jour.plusDays(1);
        }

        // 3) 历史预订（过去一周），状态为 TERMINEE
        for (int i = 1; i <= 5; i++) {
            LocalDate passe = LocalDate.now().minusDays(i);
            Salle salle = salles.get(i % salles.size());
            compteur += persister(utilisateurs.get(i % utilisateurs.size()), salle,
                    passe.atTime(CRENEAUX[2][0]), passe.atTime(CRENEAUX[2][1]),
                    MOTIFS[i % MOTIFS.length], StatutReservation.TERMINEE);
        }
        return compteur;
    }

    private int persister(Utilisateur u, Salle s, LocalDateTime debut, LocalDateTime fin,
                          String motif, StatutReservation statut) {
        Reservation r = new Reservation(u, s, debut, fin, motif);
        r.setStatut(statut);
        // 注意：IDENTITY 主键需要立即执行 INSERT 才能拿到 id，因此 Hibernate 不会对其批量插入
        em.persist(r);
        return 1;
    }
}
