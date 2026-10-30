-- ===========================================================================
-- V4：题目来源溯源 + 导入去重指纹 + 抽题索引。
--
-- 为什么需要这一版：题库从"老师手敲几十道"变成"批量导入几千道"之后，
-- 三件事会立刻不成立：
--
--   1. 题目从哪来的说不清了。出了问题（答案错、题干有争议、版权方找上门）
--      需要能顺着一条记录回到它的出处。原来的表只有 created_by，
--      只能回答"谁录的"，回答不了"抄的哪里"。
--   2. 同一批数据导两次会变成两份。老师重试、两个人各导一次、
--      换个字段顺序再导一次 —— 都会造成题库里同一道题躺着好几行，
--      组卷时还会被抽中两次，学生在同一张卷子上看到两遍同一道题。
--      靠"人工别重复点"是防不住的，必须由数据库裁决。
--   3. 自动组卷要按「科目 + 题型 + 难度」筛题，现有索引
--      idx_question_subject_type(subject, type) 少了难度这一列。
--
-- 三个设计取舍，先写在这里，避免以后被"优化"掉：
--
--   A. content_hash 允许为 NULL。
--      历史数据（V4 之前的 68 道题）没法在 SQL 里算出与 Java 侧一致的指纹 ——
--      归一化要处理全角/半角、去标点、英文小写，这些 SQL 都做不到。
--      所以迁移只加列不强行回填；NULL 被应用层视为"尚未登记指纹"，
--      导入时会先回填一次再参与去重（见 QuestionImportService#backfillHashes）。
--      MySQL 的唯一索引允许多个 NULL，所以历史行不会互相打架。
--
--   B. 唯一键是 (created_by, content_hash) 而不是 content_hash。
--      题库本来就是老师的私有资源（listQuestions 按 created_by 过滤），
--      两道题内容一样但一个在 A 老师库里、一个在 B 老师库里，是完全正常的事。
--      如果只按 content_hash 做全局唯一，B 老师就永远导不进那道题，
--      而错误信息还是"重复"，看起来像他自己操作错了。
--
--   C. 不建 (subject, type, difficulty) 的唯一或哈希索引，只建普通 KEY。
--      抽题是"取一批 id 回来自己洗牌"，不是等值查询，用不上更特殊的东西。
-- ===========================================================================

SET @db = DATABASE();

-- ---------- 1. 五个新列，逐列幂等 ----------

-- 源系统里的题目 ID。C-Eval 是形如 "computer_network-23" 的字符串，
-- MMLU 是整数，Excel 是行号 —— 统一按字符串存，不要试图统一成数字。
SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA=@db AND TABLE_NAME='question' AND COLUMN_NAME='external_id');
SET @ddl = IF(@c=0,
  'ALTER TABLE question ADD COLUMN external_id VARCHAR(128) NULL COMMENT ''源系统里的题目 ID，用于回溯''',
  'SELECT ''external_id 已存在'' AS skipped');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- manual | ceval | cmmlu | mmlu | excel | school
-- 用 short code 而不是中文，避免排序/比较受排序规则影响；展示层再翻译。
SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA=@db AND TABLE_NAME='question' AND COLUMN_NAME='source');
SET @ddl = IF(@c=0,
  'ALTER TABLE question ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT ''manual'' COMMENT ''manual|ceval|cmmlu|mmlu|excel|school''',
  'SELECT ''source 已存在'' AS skipped');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- 出处：数据集版本、文件名 + 行号、或授权合同编号。不设 NOT NULL，
-- 因为手工出的题确实没有出处，硬填一个空字符串反而让人以为填过了。
SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA=@db AND TABLE_NAME='question' AND COLUMN_NAME='source_ref');
SET @ddl = IF(@c=0,
  'ALTER TABLE question ADD COLUMN source_ref VARCHAR(255) NULL COMMENT ''出处：数据集版本/文件名+行号/授权编号''',
  'SELECT ''source_ref 已存在'' AS skipped');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- 许可证。这一列不是装饰：它决定这条数据能不能出现在对外演示、能不能进简历截图。
-- 空 = 未知（未知不等于可以随便用），这一点写在文档里。
SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA=@db AND TABLE_NAME='question' AND COLUMN_NAME='license');
SET @ddl = IF(@c=0,
  'ALTER TABLE question ADD COLUMN license VARCHAR(64) NULL COMMENT ''如 CC BY-NC-SA 4.0；NULL=未知''',
  'SELECT ''license 已存在'' AS skipped');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- 去重指纹：题型 + 归一化题干 + 归一化选项的 SHA-256（十六进制，64 字符）。
-- 刻意不包含 answer —— 同题干不同答案应当被识别为"同一道题的答案冲突"，
-- 而不是两道理所当然并存的题。冲突会在导入报告里点名，不静默覆盖。
SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA=@db AND TABLE_NAME='question' AND COLUMN_NAME='content_hash');
SET @ddl = IF(@c=0,
  'ALTER TABLE question ADD COLUMN content_hash CHAR(64) NULL COMMENT ''题型+归一化题干+选项的 SHA-256；NULL=历史数据未登记''',
  'SELECT ''content_hash 已存在'' AS skipped');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ---------- 2. 唯一键：并发导入的最终兜底 ----------
-- 应用层导入前会先查一遍重，但"先查后插"在并发下必然漏：
-- 两个请求同时查到"不存在"，然后都插进去。唯一索引是唯一挡得住的东西。
SET @idx = 'uk_question_owner_hash';
SET @e = (SELECT COUNT(*) FROM information_schema.STATISTICS
          WHERE TABLE_SCHEMA=@db AND TABLE_NAME='question' AND INDEX_NAME=@idx);
SET @ddl = IF(@e=0,
  'ALTER TABLE question ADD UNIQUE KEY uk_question_owner_hash (created_by, content_hash)',
  'SELECT ''uk_question_owner_hash 已存在'' AS skipped');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ---------- 3. 抽题索引 ----------
-- 自动组卷的查询是：
--   SELECT id FROM question WHERE created_by=? AND subject=? AND type=? AND difficulty=?
-- 旧索引 (subject, type) 的前缀用不上 created_by，而 created_by 是选择性最高的一列，
-- 放最左能先把"这个老师的题"圈出来。subject/type/difficulty 跟在后面做等值收窄。
--
-- 为什么把 created_by 放最左而不是保留旧索引：题库是私有资源，
-- 几乎每一次查题都带 created_by，不带它的查询一次都不存在。
SET @idx = 'idx_question_owner_pick';
SET @e = (SELECT COUNT(*) FROM information_schema.STATISTICS
          WHERE TABLE_SCHEMA=@db AND TABLE_NAME='question' AND INDEX_NAME=@idx);
SET @ddl = IF(@e=0,
  'ALTER TABLE question ADD KEY idx_question_owner_pick (created_by, subject, type, difficulty)',
  'SELECT ''idx_question_owner_pick 已存在'' AS skipped');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
