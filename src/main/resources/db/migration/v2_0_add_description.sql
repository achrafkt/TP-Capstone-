-- =====================================================================
-- Migration version 2.0
-- 迁移 2.0：为会议室增加描述、为用户邮箱加唯一索引、为冲突检测加索引
-- 兼容 H2 与 MySQL 8
-- =====================================================================

-- Ajout de la colonne "description" à la table "salles"
-- 为 salles 表增加 description 列
ALTER TABLE salles ADD COLUMN description VARCHAR(255);

-- Ajout d'un index unique sur la colonne "email" de la table "utilisateurs"
-- 为 utilisateurs.email 添加唯一索引（保证邮箱不重复）
CREATE UNIQUE INDEX idx_utilisateur_email ON utilisateurs (email);

-- Index composite pour accélérer la détection de conflits (salle + créneau)
-- 复合索引：加速“同一会议室、时间段重叠”的冲突查询
CREATE INDEX idx_reservation_salle_dates ON reservations (salle_id, date_debut, date_fin);
