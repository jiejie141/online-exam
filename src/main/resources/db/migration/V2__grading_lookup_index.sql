-- ===========================================================================
-- V2：updated_at（判分重试排查用）。
--
-- ★ 这一版刻意保留了两段“我原先判断错了”的记录，见文件末尾。
--    第二段是关于幂等性的，比第一段重要得多。
-- ===========================================================================

-- MySQL 没有 ADD COLUMN IF NOT EXISTS，所以要自己查 information_schema，
-- 再用 PREPARE 把 DDL 拼出来执行。
--
-- ★★ 为什么必须这么做（这是我实际踩到的坑，不是理论）：
--    application.yml 开了 baseline-on-migrate，而它判断的依据只有
--    “这个库是不是空的” —— **它无法分辨库到底处于 V1 还是 V1+V2**。
--    只要库非空，它就一律标成 V1，然后开始跑 V2。
--    于是：一个**已经手动跑过 V2** 的库（比如我之前验证迁移时跑过），
--    会被再次标成 1 -> V2 再跑一遍 -> ERROR 1060 Duplicate column。
--    表现是应用**启动直接失败**，而不是某个查询变慢。
--
--    这个错误的隐蔽之处在于：它在全新库上完全不会出现，
--    只在“已有库 + 曾经手动执行过迁移”的组合下炸，
--    而这正是从旧 schema.sql 迁移过来的真实场景。
--    让迁移自身幂等，是唯一能同时覆盖两种库的做法。

SET @db    = DATABASE();
SET @table = 'exam_record';
SET @col   = 'updated_at';

SET @exists = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = @table AND COLUMN_NAME = @col
);

SET @ddl = IF(
  @exists > 0,
  'SELECT ''updated_at 已存在，跳过'' AS skipped',
  CONCAT(
    'ALTER TABLE ', @table,
    ' ADD COLUMN ', @col, ' DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)',
    ' ON UPDATE CURRENT_TIMESTAMP(3) COMMENT ',
    CHAR(39), '最后一次变更时间，判分重试排查用', CHAR(39)
  )
);

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- 记录一：关于 idx_record_grading(status, score) —— 我原先判断错了。
--
-- 我原本的推理：漏判发现查询是 WHERE status='submitted' AND score IS NULL，
--   而已有索引 idx_record_exam_status 的前缀是 exam_id，这个查询不带 exam_id，
--   所以“索引根本用不上，是全表扫”。
--
-- 实际连真 MySQL EXPLAIN 验了一次，结论是上面这段推理错了：
--   已有索引 idx_record_deadline(status, deadline_at) 的前缀正好是 status，
--   优化器会选它 —— EXPLAIN 的 type 是 ref（不是 ALL），key_len=66。
--   也就是说这条查询从来就不是全表扫，我只是没看全已有的索引。
--
-- 把新索引加上之后的实测：EXPLAIN 仍然选 idx_record_deadline，
--   新索引只在 possible_keys 里出现、从未被选中 —— 它是**冗余索引**。
--   冗余索引的代价是真实的：占空间，且让每次写多一次索引维护，
--   而 exam_record 是写最频繁的表之一（答一道题就 UPDATE 一次）。
--
-- 最终决定：**不加这条索引**。
--
-- ALTER TABLE exam_record
--   ADD KEY idx_record_grading (status, score);
--
-- 留给以后的结论：判断“某条索引会不会被用到”必须 EXPLAIN，
-- 不能靠看索引名的前缀去推断。
