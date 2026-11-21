-- 测试专用脚本：第一条语句合法，第二条语句引用不存在的表
-- 用于验证 MigrationExecutor 在出错时会回滚整个脚本
INSERT INTO equipements (nom) VALUES ('Equipement fantome');
INSERT INTO table_inexistante (id) VALUES (1);
