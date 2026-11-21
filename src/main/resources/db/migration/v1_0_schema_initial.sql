-- =====================================================================
-- Migration version 1.0 : schéma initial de l'application de réservation
-- 迁移 1.0：创建初始表结构
-- 兼容 H2 与 MySQL 8（InnoDB）
-- =====================================================================

-- 用户表（注意：email 的唯一索引在 v2.0 中添加）
CREATE TABLE IF NOT EXISTS utilisateurs (
    id          BIGINT       AUTO_INCREMENT PRIMARY KEY,
    email       VARCHAR(150) NOT NULL,
    nom         VARCHAR(100) NOT NULL,
    departement VARCHAR(100),
    version     INT          NOT NULL DEFAULT 0
);

-- 会议室表（description 列在 v2.0 中添加）
CREATE TABLE IF NOT EXISTS salles (
    id       BIGINT       AUTO_INCREMENT PRIMARY KEY,
    nom      VARCHAR(100) NOT NULL,
    capacite INT          NOT NULL,
    batiment VARCHAR(100),
    etage    INT,
    version  INT          NOT NULL DEFAULT 0,
    CONSTRAINT uk_salle_nom UNIQUE (nom)
);

-- 设备表（只读参考数据）
CREATE TABLE IF NOT EXISTS equipements (
    id  BIGINT       AUTO_INCREMENT PRIMARY KEY,
    nom VARCHAR(100) NOT NULL,
    CONSTRAINT uk_equipement_nom UNIQUE (nom)
);

-- 会议室-设备 关联表（多对多）
CREATE TABLE IF NOT EXISTS salle_equipement (
    salle_id      BIGINT NOT NULL,
    equipement_id BIGINT NOT NULL,
    PRIMARY KEY (salle_id, equipement_id),
    CONSTRAINT fk_se_salle      FOREIGN KEY (salle_id)      REFERENCES salles (id),
    CONSTRAINT fk_se_equipement FOREIGN KEY (equipement_id) REFERENCES equipements (id)
);

-- 预订表
CREATE TABLE IF NOT EXISTS reservations (
    id             BIGINT       AUTO_INCREMENT PRIMARY KEY,
    salle_id       BIGINT       NOT NULL,
    utilisateur_id BIGINT       NOT NULL,
    date_debut     DATETIME     NOT NULL,
    date_fin       DATETIME     NOT NULL,
    motif          VARCHAR(255),
    statut         VARCHAR(20)  NOT NULL,
    date_creation  DATETIME     NOT NULL,
    version        INT          NOT NULL DEFAULT 0,
    CONSTRAINT fk_res_salle       FOREIGN KEY (salle_id)       REFERENCES salles (id),
    CONSTRAINT fk_res_utilisateur FOREIGN KEY (utilisateur_id) REFERENCES utilisateurs (id)
);
