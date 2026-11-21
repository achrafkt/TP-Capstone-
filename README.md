# TP Capstone : réservation de salles avec JPA / Hibernate

Application Java (JEE pur, **sans Spring**) qui gère la réservation de salles de réunion avec JPA 2.2 et Hibernate 5.6 :
modèle de données complet, cache de second niveau Ehcache, jeu de données réaliste, tests JUnit 5,
migrations SQL exécutées par JDBC, rapport de performance basé sur les statistiques Hibernate,
requêtes JPQL et Criteria, transactions avec verrouillage optimiste et logs `java.util.logging`.

> Les commentaires du code source sont rédigés en chinois (中文注释).
> Les messages de l'application, les logs et ce README sont en français.

---

## Sommaire

1. [Démarrage rapide](#démarrage-rapide)
2. [Stack technique](#stack-technique)
3. [Architecture du projet](#architecture-du-projet)
4. [Les 11 étapes du TP](#les-11-étapes-du-tp)
5. [Tests](#tests)
6. [Utiliser MySQL au lieu de H2](#utiliser-mysql-au-lieu-de-h2)
7. [Exemple de sortie](#exemple-de-sortie)
8. [Choix de conception](#choix-de-conception)

---

## Démarrage rapide

**Prérequis :** JDK 11 ou plus récent. Maven n'est pas nécessaire, le Maven Wrapper (`mvnw`) est fourni.

```bash
# Lancer les 43 tests
./mvnw test                # Linux / macOS / Git Bash
mvnw.cmd test              # Windows (cmd / PowerShell)

# Exécuter l'application complète (migration → données → démos → rapport)
./mvnw compile exec:java

# Même chose, sans afficher le SQL généré par Hibernate
./mvnw compile exec:java -Dhibernate.show_sql=false
```

Par défaut, l'application utilise une base **H2 en mode fichier** (`./data/reservation.mv.db`), sans rien à installer.
On peut la relancer autant de fois qu'on veut : les migrations déjà appliquées sont ignorées et les données
ne sont insérées que si la base est vide.

Fichiers générés à l'exécution (ignorés par Git) :

| Fichier | Contenu |
|---|---|
| `data/reservation.mv.db` | base H2 |
| `logs/reservation-0.log` | logs de l'application (`java.util.logging`) |
| `rapports/rapport-performance.txt` | dernier rapport de performance |

> Sous Windows, si les accents s'affichent mal dans la console, exécutez d'abord `chcp 65001`.

---

## Stack technique

| Composant | Version | Rôle |
|---|---|---|
| Java | 11+ | langage (compilé avec `--release 11`) |
| Hibernate ORM | 5.6.15.Final | implémentation JPA 2.2 (`javax.persistence`) |
| hibernate-jcache + Ehcache | 5.6.15 / 3.10.8 | cache de second niveau (JSR-107) |
| H2 | 2.2.224 | base par défaut (fichier, ou mémoire pour les tests) |
| MySQL Connector/J | 8.3.0 | base optionnelle |
| JUnit Jupiter | 5.10.2 | tests |
| java.util.logging | JDK | journalisation (aucun framework externe) |

---

## Architecture du projet

```
TP-Capstone/
├── pom.xml
├── mvnw, mvnw.cmd, .mvn/                    Maven Wrapper
└── src/
    ├── main/
    │   ├── java/com/example/
    │   │   ├── MainApp.java                  Étape 8 : orchestration complète
    │   │   ├── config/
    │   │   │   ├── DatabaseConfig.java       paramètres JDBC partagés (JDBC + JPA)
    │   │   │   └── LogConfig.java            chargement de logging.properties
    │   │   ├── model/                        Étape 1 : entités JPA
    │   │   │   ├── Utilisateur.java
    │   │   │   ├── Salle.java
    │   │   │   ├── Equipement.java
    │   │   │   ├── Reservation.java
    │   │   │   └── StatutReservation.java
    │   │   ├── repository/                   Étape 9 : requêtes JPQL / Criteria
    │   │   │   ├── ReservationRepository.java
    │   │   │   ├── CritereRecherche.java
    │   │   │   └── StatistiqueSalle.java     DTO (SELECT NEW)
    │   │   ├── service/                      Étapes 10 et 11 : métier, transactions, erreurs
    │   │   │   ├── ReservationService.java
    │   │   │   ├── ReservationException.java
    │   │   │   ├── ConflitReservationException.java
    │   │   │   └── ModificationConcurrenteException.java
    │   │   └── util/
    │   │       ├── DataInitializer.java      Étape 3 : jeu de données
    │   │       ├── MigrationExecutor.java    Étape 6 : exécution des scripts SQL
    │   │       ├── PerformanceReport.java    Étape 7 : rapport de performance
    │   │       ├── JpaUtil.java              création de l'EntityManagerFactory
    │   │       └── TransactionHelper.java    modèle begin / commit / rollback
    │   └── resources/
    │       ├── META-INF/persistence.xml      Étape 2 : unité ReservationPU + cache
    │       ├── ehcache.xml                   régions de cache Ehcache
    │       ├── logging.properties            configuration java.util.logging
    │       └── db/migration/                 Étape 5 : scripts SQL
    │           ├── v1_0_schema_initial.sql
    │           └── v2_0_add_description.sql
    └── test/
        ├── java/com/example/test/            Étape 4 : tests JUnit 5
        └── resources/                        script de test du rollback, logs de test
```

### Modèle de données

```
┌──────────────┐ 1      * ┌──────────────────┐ *      1 ┌──────────────┐
│ Utilisateur  │──────────│   Reservation    │──────────│    Salle     │
├──────────────┤          ├──────────────────┤          ├──────────────┤
│ id           │          │ id               │          │ id           │
│ email UNIQUE │          │ dateDebut        │          │ nom UNIQUE   │
│ nom          │          │ dateFin          │          │ capacite     │
│ departement  │          │ motif            │          │ batiment     │
│ @Version     │          │ statut (enum)    │          │ etage        │
└──────────────┘          │ dateCreation     │          │ description  │ ← ajoutée par v2.0
                          │ @Version         │          │ @Version     │
                          └──────────────────┘          └──────┬───────┘
                                                               │ *
                                                               │  salle_equipement
                                                               │ *
                                                        ┌──────┴───────┐
                                                        │  Equipement  │ @Immutable
                                                        │ id, nom      │
                                                        └──────────────┘
```

---

## Les 11 étapes du TP

### Étape 1 : modèle de données

- `@ManyToOne(fetch = LAZY)` de `Reservation` vers `Salle` et vers `Utilisateur`, avec `@JoinColumn(nullable = false)`.
- Côté inverse, `@OneToMany(mappedBy = ...)`, lazy par défaut.
- `@ManyToMany` entre `Salle` et `Equipement`, via la table `salle_equipement`.
- `@Version` sur toutes les entités modifiables, pour le verrouillage optimiste.
- Contraintes `nullable = false` et `unique = true` (email, nom de salle, nom d'équipement).
- Le statut est stocké en chaîne (`@Enumerated(EnumType.STRING)`), ce qui le protège d'un réordonnancement de l'enum.
- `@PrePersist` et `@PreUpdate` remplissent `dateCreation` et vérifient que `dateFin > dateDebut`.
- `equals`/`hashCode` reposent sur une clé métier (email, nom) et non sur l'id généré.

### Étape 2 : cache de second niveau

Configuration dans [`persistence.xml`](src/main/resources/META-INF/persistence.xml) : `JCacheRegionFactory`,
`EhcacheCachingProvider`, cache de requêtes et `shared-cache-mode = ENABLE_SELECTIVE`.
Les tailles et durées d'expiration de chaque région sont dans [`ehcache.xml`](src/main/resources/ehcache.xml).

Une stratégie différente est appliquée à chaque entité :

| Entité | Stratégie | Justification |
|---|---|---|
| `Equipement` | `READ_ONLY` | données de référence immuables (`@Immutable`), la stratégie la plus rapide |
| `Salle` (+ collection `equipements`) | `READ_WRITE` | lue souvent, modifiée parfois ; la cohérence est garantie par des verrous logiciels |
| `Utilisateur` | `NONSTRICT_READ_WRITE` | profils rarement modifiés, une incohérence brève est tolérée |
| `Reservation` | *non cachée* | données transactionnelles très volatiles : le cache coûterait plus qu'il ne rapporterait |

`hibernate.show_sql`, `format_sql` et `use_sql_comments` sont activés. Dans `MainApp`, le second `find()` de la
salle ne produit **aucun SELECT** dans la console : l'entité vient du cache.

### Étape 3 : jeu de données

[`DataInitializer`](src/main/java/com/example/util/DataInitializer.java) insère, dans une seule transaction :
**8 utilisateurs, 6 salles, 6 équipements et environ 100 réservations**.

- Les dates sont **dynamiques** (calculées à partir d'aujourd'hui) : 10 jours ouvrés à venir, plus un historique
  de 5 jours passés au statut `TERMINEE`.
- Les créneaux sont fixes et ne se chevauchent pas, ce qui garantit des données sans conflit (vérifié par un test).
- La graine aléatoire est fixe (`new Random(42)`), donc les données sont reproductibles. Quelques réservations sont `ANNULEE`.
- La transaction est annulée (rollback) si une insertion échoue. `initDataSiVide()` rend l'application relançable.

### Étape 4 : scénarios de test

43 tests JUnit 5, détaillés dans la section [Tests](#tests). Ils couvrent les cas normaux, les cas d'erreur
(conflits, dates invalides, entités absentes, doublons) et la concurrence.

### Étape 5 : script de migration

Le schéma est géré **uniquement par les scripts SQL**. Hibernate tourne en mode `hbm2ddl.auto=validate`
et vérifie au démarrage que les entités correspondent aux tables.

- [`v1_0_schema_initial.sql`](src/main/resources/db/migration/v1_0_schema_initial.sql) : tables, clés étrangères, contraintes.
- [`v2_0_add_description.sql`](src/main/resources/db/migration/v2_0_add_description.sql) : colonne `salles.description`,
  index unique `idx_utilisateur_email`, index composite `(salle_id, date_debut, date_fin)` pour accélérer la détection des conflits.

Les scripts sont compatibles avec **H2 et MySQL 8**.

### Étape 6 : MigrationExecutor

[`MigrationExecutor`](src/main/java/com/example/util/MigrationExecutor.java) reprend la classe de l'énoncé et l'améliore sur quatre points :

1. **Découpage du script en instructions.** MySQL refuse par défaut plusieurs instructions dans un seul `execute()`.
   Le découpage ignore les commentaires `--` et ne coupe pas sur un `;` placé dans une chaîne `'...'`.
2. **Table `schema_version`.** Chaque version n'est appliquée qu'une fois, ce qui rend l'exécution idempotente.
3. **Transaction JDBC.** En cas d'erreur, un rollback est fait et la version n'est pas enregistrée.
   Attention : sous MySQL, une instruction DDL provoque un commit implicite.
4. **Exception métier `MigrationException`.** Elle porte un message explicite (script introuvable, erreur SQL).

```java
MigrationExecutor executor = new MigrationExecutor(url, user, password);
executor.executeMigration("db/migration/v2_0_add_description.sql"); // un script
executor.executerToutesLesMigrations();                              // tous, dans l'ordre
```

### Étape 7 : rapport de performance

[`PerformanceReport`](src/main/java/com/example/util/PerformanceReport.java) lit l'API `org.hibernate.stat.Statistics`
(`hibernate.generate_statistics=true`) et rapporte :

- les sessions, les transactions, les échecs de verrou optimiste et les requêtes JDBC préparées ;
- le nombre de requêtes JPQL/Criteria, leur durée totale et la requête la plus lente ;
- les entités chargées, insérées, mises à jour et supprimées ;
- les hits, misses et puts du cache L2, **le taux de succès et le détail par région** ;
- les hits, misses et puts du cache de requêtes ;
- le **top 5 des requêtes** les plus coûteuses.

`printReport()` affiche le rapport dans la console et `ecrireDansFichier(Path)` l'enregistre dans un fichier.

### Étape 8 : MainApp

[`MainApp`](src/main/java/com/example/MainApp.java) enchaîne les opérations suivantes :

1. migration JDBC (avant Hibernate, sinon la validation du schéma échoue) ;
2. création de l'`EntityManagerFactory` ;
3. initialisation des données ;
4. démonstration du cache L2 et du cache de requêtes ;
5. démonstration du service : création, conflit, validation, verrou optimiste, annulation ;
6. démonstration des requêtes JPQL et Criteria ;
7. génération du rapport de performance.

Les exceptions sont journalisées et les ressources sont fermées dans un bloc `finally`.

### Étape 9 : JPQL et Criteria API

Les requêtes sont dans [`ReservationRepository`](src/main/java/com/example/repository/ReservationRepository.java) :

| Méthode | Technique |
|---|---|
| `findBySalleEtDateApres` | JPQL de l'énoncé |
| `findBySalleEtDateApresCriteria` | même requête écrite avec Criteria API (un test vérifie que les résultats sont identiques) |
| `rechercher(CritereRecherche)` | **Criteria dynamique** : seuls les critères non nuls deviennent des prédicats, avec `fetch` pour éviter le N+1 |
| `existeConflit` | détection de chevauchement `debut < :fin AND fin > :debut` |
| `findSallesDisponibles` | sous-requête `NOT EXISTS` |
| `statistiquesParSalle` | `SELECT NEW` (DTO), `LEFT JOIN ... ON`, `GROUP BY` |
| `findToutesLesSallesEnCache` | `@NamedQuery` et cache de requêtes (`org.hibernate.cacheable`) |

Toutes les requêtes utilisent des **paramètres liés**. Un test vérifie qu'une tentative d'injection SQL ne renvoie aucun résultat.

### Étape 10 : transactions et isolation

- [`TransactionHelper`](src/main/java/com/example/util/TransactionHelper.java) applique le modèle de l'énoncé
  (`begin`, `commit`, `rollback` si la transaction est active, `close`) une seule fois, pour tout le code.
- **Verrou optimiste** (`@Version`) : `ReservationService` détecte `OptimisticLockException`, y compris lorsqu'elle est
  encapsulée dans une `RollbackException` au moment du commit, et la convertit en `ModificationConcurrenteException`
  avec le message « Conflit détecté, veuillez réessayer ».
- **Verrou pessimiste** sur la salle (`LockModeType.PESSIMISTIC_WRITE`) pendant la création. Sans lui, deux requêtes
  concurrentes pourraient toutes deux passer la vérification de conflit puis insérer chacune une réservation
  (anomalie de *write skew*). Le verrou sérialise ces opérations salle par salle.

### Étape 11 : logs et gestion des erreurs

- `java.util.logging` est configuré dans [`logging.properties`](src/main/resources/logging.properties)
  (console et fichier tournant `logs/`, niveau `FINE` pour `com.example`, `WARNING` pour Hibernate).
  Le pont `slf4j-jdk14` redirige aussi les logs d'Ehcache vers JUL.
- Une hiérarchie d'exceptions métier (`ReservationException` → `ConflitReservationException`,
  `ModificationConcurrenteException`) permet de donner des messages clairs à l'utilisateur.
- Chaque succès est loggé en `INFO`, chaque refus métier en `WARNING` et chaque erreur technique en `SEVERE` avec la pile d'appels.

---

## Tests

```bash
./mvnw test
```

Chaque classe de test crée sa **propre base H2 en mémoire**, applique **les vraies migrations**, puis démarre Hibernate
en mode `validate`. Les tests vérifient donc aussi que les scripts SQL correspondent aux entités.
Avant chaque test, les données sont réinitialisées et le cache L2 est vidé.

| Classe | Tests | Ce qui est vérifié |
|---|---|---|
| `ReservationTest` | 16 | test de l'énoncé, CRUD, créneaux adjacents, conflits (chevauchement, inclusion, modification), dates invalides, entités inexistantes, unicité de l'email, verrou optimiste (deux `EntityManager`, objet détaché, version périmée) |
| `CacheTest` | 7 | lecture depuis le cache L2 sans SQL, cache L1, cohérence `READ_WRITE`, cache de collection, hit du cache de requêtes, invalidation de ce cache, `Reservation` absente du cache |
| `RequeteTest` | 7 | JPQL et Criteria identiques, protection contre l'injection, Criteria dynamique, salles disponibles, statistiques `SELECT NEW` |
| `MigrationExecutorTest` | 7 | ordre d'application, idempotence, index unique effectif, état avant v2.0, rollback en cas d'erreur, script introuvable, découpage du SQL |
| `DataInitializerTest` | 4 | volumes, absence de chevauchement, dates dynamiques, pas de doublons |
| `PerformanceReportTest` | 2 | compteurs et taux de succès exacts, écriture du rapport dans un fichier |

```
[INFO] Tests run: 43, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

> Pendant les tests, des lignes `ERROR`/`WARN` d'Hibernate apparaissent (`StaleStateException`, `Unique index violation`).
> Elles sont **attendues** : elles viennent des tests qui provoquent volontairement un conflit de version ou un doublon.

---

## Utiliser MySQL au lieu de H2

1. Créer la base, ou laisser `createDatabaseIfNotExist=true` s'en charger.
2. Passer les paramètres de connexion, sous forme de propriétés système ou de variables d'environnement
   `DB_URL`, `DB_USER` et `DB_PASSWORD` :

```bash
./mvnw compile exec:java \
  -Ddb.url="jdbc:mysql://localhost:3306/reservation?createDatabaseIfNotExist=true" \
  -Ddb.user=root \
  -Ddb.password=password
```

Le driver est choisi d'après l'URL et Hibernate détecte le dialecte automatiquement.
La même configuration ([`DatabaseConfig`](src/main/java/com/example/config/DatabaseConfig.java)) sert à
`MigrationExecutor` (JDBC) et à l'`EntityManagerFactory` (JPA), ce qui garantit qu'ils travaillent sur la même base.

N'importe quelle propriété Hibernate peut aussi être surchargée en ligne de commande, par exemple
`-Dhibernate.show_sql=false` ou `-Dhibernate.format_sql=false`.

---

## Exemple de sortie

Exécution de `./mvnw compile exec:java -Dhibernate.show_sql=false` (extraits) :

```
INFO    [MigrationExecutor] Application de la migration 1.0 (5 instruction(s)) : db/migration/v1_0_schema_initial.sql
INFO    [MigrationExecutor] Application de la migration 2.0 (3 instruction(s)) : db/migration/v2_0_add_description.sql
INFO    [DataInitializer] Jeu de données créé : 8 utilisateurs, 6 salles, 6 équipements, 105 réservations

################ Cache de second niveau ################
EM n°1 : Salle{id=1, nom='Salle A', capacite=20} -> [Vidéoprojecteur, Tableau blanc]
EM n°2 (depuis le cache L2, aucun SELECT) : Salle{id=1, nom='Salle A', capacite=20} -> [Vidéoprojecteur, Tableau blanc]

################ Service de réservation ################
Créée : Reservation{id=106, debut=2026-12-21T09:00, fin=2026-12-21T11:00, statut=CONFIRMEE, version=0}
Conflit attendu : La salle « Salle A » est déjà réservée entre 2026-12-21T10:00 et 2026-12-21T12:00
Erreur de validation attendue : La date de fin doit être postérieure à la date de début
Bruno a modifié la réservation : version 0 -> 1
Verrou optimiste : Conflit détecté : la réservation a été modifiée par un autre utilisateur, veuillez réessayer.
Statut final : ANNULEE

################ Requêtes JPQL et Criteria API ################
Réservations futures de la Salle A : JPQL=21, Criteria=21
Salles libres demain 10h30-11h30 (>= 6 places) : [Salle B, Salle Innovation, Salle Conseil, Amphithéâtre]
```

Le rapport de performance correspondant :

```
-- Cache de second niveau ------------------------------------
  Hits                                   : 12
  Misses                                 : 2
  Mises à jour (put)                     : 23
  Taux de succès                         : 85.7 %
    Equipement                           hits=2     misses=0     puts=2
    Salle                                hits=7     misses=1     puts=6
    Salle.equipements                    hits=1     misses=1     puts=1
    Utilisateur                          hits=2     misses=0     puts=14

-- Cache de requêtes -----------------------------------------
  Hits                                   : 1
  Misses                                 : 1

-- Top 5 des requêtes (durée totale) -------------------------
     1 exec | total   19 ms | moy  19 ms | SELECT r FROM Reservation r WHERE r.salle.nom = :nomSalle AND r.dat...
     3 exec | total   16 ms | moy   5 ms | SELECT COUNT(r) FROM Reservation r WHERE r.salle.id = :salleId AND ...
```

Les identifiants, les dates et les durées varient d'une exécution à l'autre.

---

## Choix de conception

- **JEE pur, sans Spring.** L'unité de persistance est en `RESOURCE_LOCAL` et les transactions sont gérées explicitement avec `EntityTransaction`.
- **`javax.persistence` (JPA 2.2)** pour rester fidèle aux exemples de l'énoncé, d'où Hibernate 5.6 et non 6.x, qui utilise `jakarta.persistence`.
- **Un `EntityManager` par unité de travail** dans le service. L'`EntityManagerFactory` est unique et partagée.
- **Schéma piloté par les migrations** plutôt que par `hbm2ddl=update`, avec validation au démarrage : c'est ainsi qu'on procède en production.
- **H2 par défaut**, pour que le projet se clone et s'exécute sans installer de base. MySQL reste disponible.
- **IDENTITY et batch.** Avec `GenerationType.IDENTITY` (imposé par l'énoncé), Hibernate ne peut pas regrouper les
  INSERT en lots, car il doit connaître l'id immédiatement. `hibernate.jdbc.batch_size` profite donc surtout aux UPDATE.

### Pistes d'évolution

- interface web JSF ou JSP ;
- API REST JAX-RS ;
- notifications par email ou SMS ;
- cache distribué (cluster Ehcache, Infinispan) ;
- Flyway ou Liquibase à la place du `MigrationExecutor` maison.
