-- 演示数据：1 名老师、3 名学生、10 道题、1 场已发布的考试
--
-- 口令不是明文：下面是 BCrypt 哈希，对应
--   teacher123（老师 t001）
--   student123（学生 s001 / s002 / s003）
-- 明文只写在注释里用于本地演示，生产环境不能这么干。
--
-- 用法（schema.sql 之后执行）：
--   docker exec -i exam-mysql mysql -uroot -proot < src/main/resources/db/seed.sql

USE online_exam;

-- ---------- 先清干净 ----------
-- 这是"演示数据"脚本，重复执行必须得到同一份数据，否则第二次跑会插出 20 道题、
-- 卷面变成 20 题 200 分，后面所有断言都会莫名其妙地失败。
-- 所以这里先把业务表清掉并把自增复位 —— 别在有任何真实数据的库上跑这个脚本。
DELETE FROM exam_answer;
DELETE FROM exam_record;
DELETE FROM exam_question;
DELETE FROM question;
DELETE FROM exam;
DELETE FROM exam_user;
ALTER TABLE exam_user AUTO_INCREMENT = 1;
ALTER TABLE question AUTO_INCREMENT = 1;
ALTER TABLE exam AUTO_INCREMENT = 1;
ALTER TABLE exam_question AUTO_INCREMENT = 1;

-- ---------- 用户 ----------
INSERT INTO exam_user (username, password_hash, real_name, role, status) VALUES
  ('t001', '$2a$10$cpBeoGcWYyxORRfCrAMZpuBILTQr09RxFpm1qeIMz07w4VyY.Kf3a', '张老师', 'teacher', 'active'),
  ('s001', '$2a$10$3ivThx2CdgB4bsUBC/sds.hDffDmF4SK6wkqla9JMYkh7HrSlAUyG', '李雷',   'student', 'active'),
  ('s002', '$2a$10$3ivThx2CdgB4bsUBC/sds.hDffDmF4SK6wkqla9JMYkh7HrSlAUyG', '韩梅梅', 'student', 'active'),
  ('s003', '$2a$10$3ivThx2CdgB4bsUBC/sds.hDffDmF4SK6wkqla9JMYkh7HrSlAUyG', '王小明', 'student', 'active')
ON DUPLICATE KEY UPDATE real_name = VALUES(real_name);

-- ---------- 题目 ----------
-- 6 道单选（各 10 分）+ 2 道多选（各 15 分）+ 2 道判断（各 5 分）= 100 分
INSERT INTO question (subject, type, stem, options, answer, score, difficulty, created_by) VALUES
('Java', 'single', 'Java 中的 String 对象创建之后还能被修改吗？',
 '[{"key":"A","text":"可以，用 append 追加"},{"key":"B","text":"不可以，String 是不可变的"},{"key":"C","text":"取决于有没有声明 final"},{"key":"D","text":"只有常量池里的不可变"}]',
 'B', 10, 1, 1),

('Java', 'single', '下列哪个集合类是线程安全的？',
 '[{"key":"A","text":"ArrayList"},{"key":"B","text":"HashMap"},{"key":"C","text":"ConcurrentHashMap"},{"key":"D","text":"LinkedList"}]',
 'C', 10, 1, 1),

('Java', 'single', 'JDK 8 的 HashMap 中，链表长度超过多少且数组长度达到 64 时会转成红黑树？',
 '[{"key":"A","text":"6"},{"key":"B","text":"8"},{"key":"C","text":"10"},{"key":"D","text":"12"}]',
 'B', 10, 2, 1),

('Java', 'single', '关于 synchronized，下列说法正确的是？',
 '[{"key":"A","text":"只能修饰方法，不能修饰代码块"},{"key":"B","text":"是可重入锁"},{"key":"C","text":"等待时可以被中断"},{"key":"D","text":"必须配合 Lock 接口使用"}]',
 'B', 10, 2, 1),

('MySQL', 'single', 'InnoDB 默认的事务隔离级别是？',
 '[{"key":"A","text":"读未提交"},{"key":"B","text":"读已提交"},{"key":"C","text":"可重复读"},{"key":"D","text":"串行化"}]',
 'C', 10, 2, 1),

('MySQL', 'single', '下列哪个 WHERE 条件最可能导致普通 B+ 树索引失效？',
 '[{"key":"A","text":"id = 1"},{"key":"B","text":"name LIKE ''abc%''"},{"key":"C","text":"name LIKE ''%abc''"},{"key":"D","text":"age >= 18"}]',
 'C', 10, 3, 1),

('Java', 'multiple', '以下哪些属于 Spring Bean 的作用域？（多选）',
 '[{"key":"A","text":"singleton"},{"key":"B","text":"prototype"},{"key":"C","text":"request"},{"key":"D","text":"session"}]',
 'ABCD', 15, 2, 1),

('MySQL', 'multiple', '关于数据库索引，下列说法正确的有？（多选）',
 '[{"key":"A","text":"合适的索引能加快查询"},{"key":"B","text":"索引会拖慢写入"},{"key":"C","text":"联合索引遵循最左前缀原则"},{"key":"D","text":"索引建得越多越好"}]',
 'ABC', 15, 3, 1),

('Java', 'judge', 'ArrayList 扩容时会创建一个更大的新数组并把旧元素复制过去。',
 NULL, 'T', 5, 1, 1),

('Java', 'judge', '在 Java 中，== 比较的是对象的内容而不是引用地址。',
 NULL, 'F', 5, 1, 1)
ON DUPLICATE KEY UPDATE stem = VALUES(stem);

-- ---------- 考试 ----------
-- 时间窗用相对时间：昨天开考、7 天后关闭，任何时候执行种子脚本都能直接考
INSERT INTO exam (title, subject, start_at, end_at, duration_minutes, total_score, pass_score, status, created_by)
VALUES ('Java 后端基础月考（第 1 期）', 'Java',
        NOW() - INTERVAL 1 DAY, NOW() + INTERVAL 7 DAY,
        60, 100, 60, 'published', 1)
ON DUPLICATE KEY UPDATE title = VALUES(title);

-- ---------- 组卷 ----------
-- 分值写在关联表上：同一道题换个考试可以重新定权重
INSERT INTO exam_question (exam_id, question_id, score, seq)
SELECT e.id, q.id,
       CASE WHEN q.type = 'single' THEN 10 WHEN q.type = 'multiple' THEN 15 ELSE 5 END,
       q.id - (SELECT MIN(id) FROM question) + 1
FROM exam e JOIN question q
WHERE e.title = 'Java 后端基础月考（第 1 期）'
ON DUPLICATE KEY UPDATE score = VALUES(score);
