-- ===========================================================================
-- V3：老师端考试列表的分页查询索引。
--
-- 老师端列表的查询是：
--   SELECT ... FROM exam WHERE created_by = ? ORDER BY start_at DESC LIMIT ?, ?
-- 原来 exam 表只有 idx_exam_status_end(status, end_at)，它的前缀是 status，
-- 这个查询不带 status，所以这条索引完全用不上 —— 实际是全表扫 + filesort。
--
-- 为什么现在才加：分页之前这个查询也是全表扫，但一次就把几十行全捞回来了，
-- 表小的时候看不出差别。加上分页之后它变成「每次都扫全表，只取 20 行」，
-- 代价反而更明显了 —— 数据量一上来，翻到第 50 页和翻到第 1 页一样慢。
--
-- (created_by, start_at) 的顺序不是随手写的：
--   created_by 是等值条件，放最左；start_at 是排序列，放右边。
--   这样 ORDER BY start_at DESC 能直接用索引的有序性，不再需要 filesort。
--   反过来写成 (start_at, created_by) 就退化成「先按时间全表排一遍再过滤」。
--
-- 为什么不加 (created_by, start_at DESC)：MySQL 8.0 支持降序索引，
-- 但 B+ 树正向扫描和反向扫描的代价一样，DESC 索引在这里没有收益，
-- 反而让 DBA 在 SHOW INDEX 里多看一眼。
-- ===========================================================================
-- 幂等：MySQL 没有 CREATE INDEX IF NOT EXISTS，用 information_schema 先查。
-- 理由同 V2 —— baseline-on-migrate 无法分辨库处于哪个版本，迁移必须自己能重跑。
SET @db    = DATABASE();
SET @table = 'exam';
SET @index = 'idx_exam_created_start';

SET @exists = (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = @table AND INDEX_NAME = @index
);

SET @ddl = IF(
  @exists > 0,
  'SELECT ''idx_exam_created_start 已存在，跳过'' AS skipped',
  'ALTER TABLE exam ADD KEY idx_exam_created_start (created_by, start_at)'
);

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
