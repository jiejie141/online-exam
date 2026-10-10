-- ===========================================================================
-- V2：updated_at（判分重试排查用）。
--
-- ★ 这一版刻意保留了一条“我原先判断错了”的记录，见文件末尾。
-- ===========================================================================

-- updated_at：判分重试要记录「上次尝试判分是什么时候」。
-- 原来没有这个字段就只能靠 submitted_at 推断，而那是交卷时间不是判分时间。
-- ON UPDATE CURRENT_TIMESTAMP 让它在任何 UPDATE 时自动维护，
-- 不依赖应用层记得去写 —— 忘了写是这类字段最常见的失效方式。
ALTER TABLE exam_record
  ADD COLUMN updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后一次变更时间，判分重试排查用';

-- ---------------------------------------------------------------------------
-- 关于 idx_record_grading(status, score) —— 这条索引是我**原先判断错了**，
-- 下面整段留着做记录，不删。
--
-- 我原本的推理：漏判发现查询是 WHERE status='submitted' AND score IS NULL，
--   而已有索引 idx_record_exam_status 的前缀是 exam_id，这个查询不带 exam_id，
--   所以“索引根本用不上，是全表扫”。
--
-- 实际连真 MySQL 用 EXPLAIN 验了一次，结论是上面这段推理错了：
--   已有索引 idx_record_deadline(status, deadline_at) 的前缀正好是 status，
--   优化器会选它 —— EXPLAIN 的 type 是 ref（不是 ALL），key_len=66。
--   也就是说这条查询从来就不是全表扫，我只是没看全已有的索引。
--
-- 把新索引加上之后的实测：EXPLAIN 仍然选 idx_record_deadline，
--   新索引只在 possible_keys 里出现、从未被选中 —— 它是**冗余索引**。
--   冗余索引的代价是真实的：占空间，且让每次写多一次索引维护，
--   而 exam_record 是写最频繁的表之一（答一道题就 UPDATE 一次）。
--
-- 最终决定：**不加这条索引**。这个迁移只保留 updated_at 那一列。
--
-- ALTER TABLE exam_record
--   ADD KEY idx_record_grading (status, score);
--
-- 留给以后的结论：判断“某条索引会不会被用到”必须 EXPLAIN，
-- 不能靠看索引名的前缀去推断。
