-- 在线考试与自动判分系统 · 业务表
--
-- 设计约定：
-- 1. 分数一律用 INT 整分（考试里不会出现 2.5 分这种需要小数的场景）。
--    真需要半分时再改，不要提前为不存在的情况增加复杂度。
-- 2. 每张表上的索引都注明服务于哪条查询。exam_answer 是写最频繁的表，
--    索引多了会拖慢交卷。
-- 3. 需要"恰好一次"的地方交给唯一索引裁决：一个学生一场考试只应有一条记录，
--    一道题在一份答卷里只应有一条作答 —— 都靠唯一键挡住，不靠应用层先查后插。

CREATE DATABASE IF NOT EXISTS online_exam
  DEFAULT CHARACTER SET utf8mb4 DEFAULT COLLATE utf8mb4_0900_ai_ci;
USE online_exam;

-- ---------- 用户（学生 / 老师） ----------
-- 表名用 exam_user 而不是 user：user 是 MySQL 的内置函数名，
-- 写成 `user` 每次都要反引号，迟早有人漏掉。
CREATE TABLE IF NOT EXISTS exam_user (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  username      VARCHAR(64)  NOT NULL,
  password_hash VARCHAR(128) NOT NULL COMMENT 'BCrypt（自带随机盐），明文不落库',
  real_name     VARCHAR(64)  NOT NULL,
  role          VARCHAR(16)  NOT NULL DEFAULT 'student' COMMENT 'student | teacher',
  status        VARCHAR(16)  NOT NULL DEFAULT 'active',
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_exam_user_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------- 题库 ----------
-- 选项存 JSON 而不是拆成 question_option 表：
-- 选项永远跟着题目整体读写，从不单独查询"所有题目的第 3 个选项"。
-- 拆表只会让每次取题都多一次 JOIN，换不来任何查询能力。
CREATE TABLE IF NOT EXISTS question (
  id         BIGINT       NOT NULL AUTO_INCREMENT,
  subject    VARCHAR(64)  NOT NULL COMMENT '科目',
  type       VARCHAR(16)  NOT NULL COMMENT 'single | multiple | judge',
  stem       VARCHAR(1000) NOT NULL COMMENT '题干',
  options    JSON         NULL COMMENT '如 [{"key":"A","text":"..."}]；判断题为空',
  answer     VARCHAR(64)  NOT NULL COMMENT '单选 A / 多选 ABD / 判断 T|F',
  score      INT          NOT NULL DEFAULT 5 COMMENT '默认分值',
  difficulty TINYINT      NOT NULL DEFAULT 1 COMMENT '1 易 2 中 3 难',
  created_by BIGINT       NULL,
  created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  -- 组卷时按"科目 + 题型"筛题，走这条索引
  KEY idx_question_subject_type (subject, type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------- 考试 ----------
CREATE TABLE IF NOT EXISTS exam (
  id               BIGINT       NOT NULL AUTO_INCREMENT,
  title            VARCHAR(128) NOT NULL,
  subject          VARCHAR(64)  NOT NULL,
  -- 开考时间窗与时长是两回事：在窗口内任意时刻进入都能考满 duration 分钟，
  -- 但一旦窗口关闭就不允许再进入。合并成一个字段会做不出"随到随考"。
  start_at         DATETIME(3)  NOT NULL,
  end_at           DATETIME(3)  NOT NULL,
  duration_minutes INT          NOT NULL DEFAULT 60,
  total_score      INT          NOT NULL DEFAULT 100,
  pass_score       INT          NOT NULL DEFAULT 60,
  status           VARCHAR(16)  NOT NULL DEFAULT 'draft' COMMENT 'draft | published | closed',
  created_by       BIGINT       NULL,
  created_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  -- 定时任务扫描"该关的考试"：WHERE status='published' AND end_at <= ?
  KEY idx_exam_status_end (status, end_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------- 试卷组成（考试 ↔ 题目） ----------
CREATE TABLE IF NOT EXISTS exam_question (
  id          BIGINT NOT NULL AUTO_INCREMENT,
  exam_id     BIGINT NOT NULL,
  question_id BIGINT NOT NULL,
  -- 分值存在关联表而不是题目表：同一道题在不同考试里可以有不同权重
  score       INT    NOT NULL DEFAULT 5,
  seq         INT    NOT NULL DEFAULT 0 COMMENT '卷面顺序',
  PRIMARY KEY (id),
  UNIQUE KEY uk_exam_question (exam_id, question_id),
  -- 取整张试卷按序输出：WHERE exam_id=? ORDER BY seq
  KEY idx_exam_question_seq (exam_id, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------- 考试记录 ----------
CREATE TABLE IF NOT EXISTS exam_record (
  id               BIGINT      NOT NULL AUTO_INCREMENT,
  exam_id          BIGINT      NOT NULL,
  user_id          BIGINT      NOT NULL,
  status           VARCHAR(16) NOT NULL DEFAULT 'ongoing' COMMENT 'ongoing | submitted | graded',
  score            INT         NULL COMMENT '判分后写入，未判分是 NULL 而不是 0',
  started_at       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  -- 应截止时刻：进入考试时就固定下来（开始时间 + 时长），
  -- 不每次都现算 —— 否则改了考试时长会让正在考的人凭空多出时间。
  deadline_at      DATETIME(3) NOT NULL,
  submitted_at     DATETIME(3) NULL,
  duration_seconds INT         NULL,
  PRIMARY KEY (id),
  -- 一个学生一场考试只能有一条记录：重复交卷、页面刷新重进都靠它挡住
  UNIQUE KEY uk_record_exam_user (exam_id, user_id),
  -- "我的考试列表"：WHERE user_id=? ORDER BY started_at DESC
  KEY idx_record_user_time (user_id, started_at),
  -- 老师端实时统计已交卷人数：WHERE exam_id=? AND status=?
  KEY idx_record_exam_status (exam_id, status),
  -- 定时任务扫描超时未交卷：WHERE status='ongoing' AND deadline_at <= ? ORDER BY deadline_at
  KEY idx_record_deadline (status, deadline_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------- 作答明细 ----------
CREATE TABLE IF NOT EXISTS exam_answer (
  id          BIGINT      NOT NULL AUTO_INCREMENT,
  record_id   BIGINT      NOT NULL,
  question_id BIGINT      NOT NULL,
  user_answer VARCHAR(64) NULL COMMENT '未作答为 NULL，与"答错"要区分开',
  correct     TINYINT(1)  NULL COMMENT '判分后写入',
  score_got   INT         NOT NULL DEFAULT 0,
  answered_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  -- 同一份答卷里一道题只能有一条作答：自动保存反复覆盖时靠它做 upsert
  UNIQUE KEY uk_answer_record_question (record_id, question_id),
  -- 判分时一次性取出整份答卷：WHERE record_id=?
  KEY idx_answer_record (record_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
