-- 演示数据：1 名老师、3 名学生、68 道题、1 场已发布的考试
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
-- 这是"演示数据"脚本，重复执行必须得到同一份数据，否则第二次跑会插出双倍的题、
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
-- 前 10 道是「Java 后端基础月考（第 1 期）」的卷面题（见文件末尾的组卷语句），
-- 分值 6×10 + 2×15 + 2×5 = 100 分，e2e 脚本手算的 87 分就是基于这 10 道。
-- 后面 58 道只进题库、不进这场考试 —— 题库要看起来像个真在用的库，
-- 但演示卷必须稳定，否则 e2e 的「卷面 10 道题」断言每改一次题库就要跟着改。
--
-- 选项一律按真实题目写：题干和四个选项都是能查证的，干扰项是常见误解而不是乱填。
-- 这比「选项 A / 选项 B」有意义得多 —— 演示时别人一眼就能看出这些题是真的。
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
 NULL, 'F', 5, 1, 1),

-- ---------- 题库：Java（12 道） ----------
('Java', 'single', '用 final 修饰一个对象引用后，下列说法正确的是？',
 '[{"key":"A","text":"引用不能再指向别的对象，但它指向的对象内容可以改"},{"key":"B","text":"引用和对象内容都完全不能再改"},{"key":"C","text":"引用可以改指，对象内容不能改"},{"key":"D","text":"等价于把对象做成了不可变对象"}]',
 'A', 5, 2, 1),

('Java', 'single', 'volatile 关键字能保证下列哪一项？',
 '[{"key":"A","text":"复合操作的原子性"},{"key":"B","text":"变量的可见性并禁止指令重排序"},{"key":"C","text":"整个方法的互斥执行"},{"key":"D","text":"线程的公平调度"}]',
 'B', 5, 3, 1),

('Java', 'single', 'Integer 的自动装箱缓存（IntegerCache）默认覆盖的范围是？',
 '[{"key":"A","text":"-128 到 127"},{"key":"B","text":"0 到 255"},{"key":"C","text":"-256 到 256"},{"key":"D","text":"只缓存 0"}]',
 'A', 5, 3, 1),

('Java', 'single', 'try-with-resources 要求被管理的资源实现哪个接口？',
 '[{"key":"A","text":"Serializable"},{"key":"B","text":"Closeable 或 AutoCloseable"},{"key":"C","text":"Runnable"},{"key":"D","text":"Comparable"}]',
 'B', 5, 2, 1),

('Java', 'single', '线程池的 corePoolSize 个核心线程都在忙时，新提交的任务会先进入哪里？',
 '[{"key":"A","text":"直接创建非核心线程"},{"key":"B","text":"进入工作队列排队"},{"key":"C","text":"直接抛 RejectedExecutionException"},{"key":"D","text":"阻塞提交线程直到有空闲线程"}]',
 'B', 5, 3, 1),

('Java', 'single', '关于 Java 8 的接口默认方法（default method），下列说法正确的是？',
 '[{"key":"A","text":"接口可以有带方法体的默认实现，实现类不强制重写"},{"key":"B","text":"接口仍然不能有任何方法体"},{"key":"C","text":"默认方法必须是 static 的"},{"key":"D","text":"默认方法不能被实现类覆盖"}]',
 'A', 5, 2, 1),

('Java', 'single', 'Java 异常体系中，Error 通常表示什么？',
 '[{"key":"A","text":"可以被业务代码捕获并恢复的错误"},{"key":"B","text":"程序无法处理的严重问题，不应被捕获"},{"key":"C","text":"受检异常的一种"},{"key":"D","text":"编译器产生的语法错误"}]',
 'B', 5, 2, 1),

('Java', 'single', 'String s = "a" + "b"; 在编译期会发生什么？',
 '[{"key":"A","text":"运行时用 StringBuilder 拼接"},{"key":"B","text":"编译器把它折叠成常量「ab」"},{"key":"C","text":"创建两个对象再合并"},{"key":"D","text":"编译报错，字面量不能相加"}]',
 'B', 5, 3, 1),

('Java', 'single', 'ArrayList 与 LinkedList 相比，随机访问 get(i) 的时间复杂度分别是？',
 '[{"key":"A","text":"ArrayList O(1)，LinkedList O(n)"},{"key":"B","text":"两者都是 O(1)"},{"key":"C","text":"ArrayList O(n)，LinkedList O(1)"},{"key":"D","text":"两者都是 O(n)"}]',
 'A', 5, 1, 1),

('Java', 'single', '把对象放进 HashMap 作为 key，最少必须正确重写哪两个方法？',
 '[{"key":"A","text":"toString 与 clone"},{"key":"B","text":"hashCode 与 equals"},{"key":"C","text":"compareTo 与 hashCode"},{"key":"D","text":"equals 与 finalize"}]',
 'B', 5, 2, 1),

('Java', 'single', '下列哪种写法能正确避免 finally 块覆盖掉 return 的返回值？',
 '[{"key":"A","text":"在 finally 里再写一个 return"},{"key":"B","text":"finally 里不要写 return，只做资源释放"},{"key":"C","text":"把 return 放到 finally 之后"},{"key":"D","text":"用 System.exit 结束方法"}]',
 'B', 5, 3, 1),

('Java', 'multiple', '以下哪些是 Java 中创建线程的方式？（多选）',
 '[{"key":"A","text":"继承 Thread 并重写 run"},{"key":"B","text":"实现 Runnable 交给 Thread"},{"key":"C","text":"实现 Callable 交给线程池"},{"key":"D","text":"直接 new Thread 并调用 run 方法启动"}]',
 'ABC', 5, 2, 1),

-- ---------- 题库：MySQL（10 道） ----------
('MySQL', 'single', 'EXPLAIN 结果中 type 列为 ALL 通常说明什么？',
 '[{"key":"A","text":"使用了唯一索引"},{"key":"B","text":"全表扫描"},{"key":"C","text":"使用了覆盖索引"},{"key":"D","text":"只扫描了一行"}]',
 'B', 5, 2, 1),

('MySQL', 'single', 'InnoDB 中，通过二级索引查询非索引列时通常需要做什么？',
 '[{"key":"A","text":"回表，用主键再查一次聚簇索引"},{"key":"B","text":"直接读二级索引就够了"},{"key":"C","text":"必须先做全表扫描"},{"key":"D","text":"需要重建索引"}]',
 'A', 5, 3, 1),

('MySQL', 'single', '关于 COUNT(*) 与 COUNT(1)，下列说法正确的是？',
 '[{"key":"A","text":"COUNT(*) 一定比 COUNT(1) 慢很多"},{"key":"B","text":"InnoDB 下两者性能基本一致，优化器会做同样处理"},{"key":"C","text":"COUNT(1) 会跳过 NULL 行"},{"key":"D","text":"COUNT(*) 统计的是列数"}]',
 'B', 5, 3, 1),

('MySQL', 'single', 'utf8 与 utf8mb4 的关键区别是？',
 '[{"key":"A","text":"utf8mb4 支持 4 字节字符（如 emoji），utf8 最多 3 字节"},{"key":"B","text":"两者完全等价，只是名字不同"},{"key":"C","text":"utf8 支持更多字符"},{"key":"D","text":"utf8mb4 不支持中文"}]',
 'A', 5, 2, 1),

('MySQL', 'single', '对没有命中索引的列执行 UPDATE，最可能的后果是？',
 '[{"key":"A","text":"只锁住被修改的那一行"},{"key":"B","text":"扫描并锁定大量行，容易造成锁等待"},{"key":"C","text":"语句直接报错"},{"key":"D","text":"自动为这一列建索引"}]',
 'B', 5, 3, 1),

('MySQL', 'single', '联合索引 (a, b, c) 能有效支持下列哪种查询条件？',
 '[{"key":"A","text":"只有 b = 1"},{"key":"B","text":"只有 c = 1"},{"key":"C","text":"a = 1 且 b = 2"},{"key":"D","text":"b = 2 且 c = 3"}]',
 'C', 5, 3, 1),

('MySQL', 'single', '事务 ACID 中的隔离性主要由什么来保证？',
 '[{"key":"A","text":"锁机制与 MVCC"},{"key":"B","text":"redo log"},{"key":"C","text":"undo log"},{"key":"D","text":"binlog"}]',
 'A', 5, 3, 1),

('MySQL', 'single', 'binlog 的主要用途是？',
 '[{"key":"A","text":"崩溃恢复时回滚未提交事务"},{"key":"B","text":"主从复制与数据恢复"},{"key":"C","text":"加速 SELECT 查询"},{"key":"D","text":"存储索引结构"}]',
 'B', 5, 2, 1),

('MySQL', 'multiple', '下列哪些情况可能导致索引失效？（多选）',
 '[{"key":"A","text":"在索引列上使用函数，如 WHERE YEAR(created_at) = 2026"},{"key":"B","text":"以通配符开头的 LIKE，如 LIKE ''%abc''"},{"key":"C","text":"隐式类型转换，如字符串列与数字比较"},{"key":"D","text":"使用等值查询 WHERE id = 1"}]',
 'ABC', 5, 3, 1),

('MySQL', 'multiple', '关于事务的四个特性，下列说法正确的有？（多选）',
 '[{"key":"A","text":"原子性指事务内的操作要么全做要么全不做"},{"key":"B","text":"一致性指事务前后数据都满足业务约束"},{"key":"C","text":"持久性指提交后的修改不会因宕机丢失"},{"key":"D","text":"隔离性指事务之间完全不需要任何并发控制"}]',
 'ABC', 5, 2, 1),

-- ---------- 题库：Redis（8 道） ----------
('Redis', 'single', 'Redis 单线程模型仍然很快，主要原因是？',
 '[{"key":"A","text":"数据全在内存，且用 IO 多路复用处理网络请求"},{"key":"B","text":"它把每条命令都编译成了机器码"},{"key":"C","text":"它默认关闭了持久化，所以不写磁盘"},{"key":"D","text":"它把请求分发给了多个工作线程并行执行"}]',
 'A', 5, 2, 1),

('Redis', 'single', 'SET key value NX EX 10 这条命令常被用来实现什么？',
 '[{"key":"A","text":"带过期时间的分布式锁"},{"key":"B","text":"消息队列的发布订阅"},{"key":"C","text":"一致性哈希的分片"},{"key":"D","text":"事务的回滚"}]',
 'A', 5, 2, 1),

('Redis', 'single', 'RDB 与 AOF 两种持久化方式的主要区别是？',
 '[{"key":"A","text":"RDB 存数据快照，AOF 追加写命令日志"},{"key":"B","text":"RDB 存命令日志，AOF 存快照"},{"key":"C","text":"两者都是存快照，只是压缩算法不同"},{"key":"D","text":"AOF 不需要写磁盘"}]',
 'A', 5, 3, 1),

('Redis', 'single', '缓存穿透的典型解法是？',
 '[{"key":"A","text":"给热点 key 加长 TTL"},{"key":"B","text":"缓存空结果或使用布隆过滤器拦截不存在的 key"},{"key":"C","text":"把 Redis 换成集群模式"},{"key":"D","text":"把过期时间全部设成一样"}]',
 'B', 5, 3, 1),

('Redis', 'single', '缓存雪崩最常见的诱因是？',
 '[{"key":"A","text":"大量 key 在同一时刻集中过期"},{"key":"B","text":"Redis 使用了 RDB 持久化"},{"key":"C","text":"客户端连接数太少"},{"key":"D","text":"value 的体积太大"}]',
 'A', 5, 2, 1),

('Redis', 'single', 'Redis 的 ZSET 底层主要使用什么数据结构？',
 '[{"key":"A","text":"跳表与哈希表"},{"key":"B","text":"红黑树"},{"key":"C","text":"B+ 树"},{"key":"D","text":"链表与数组"}]',
 'A', 5, 3, 1),

('Redis', 'multiple', '下列哪些是 Redis 常见的使用场景？（多选）',
 '[{"key":"A","text":"缓存"},{"key":"B","text":"分布式锁"},{"key":"C","text":"排行榜（ZSET）"},{"key":"D","text":"复杂多表关联查询"}]',
 'ABC', 5, 1, 1),

('Redis', 'judge', 'Redis 的单个命令是原子执行的，但多个命令组成的事务默认不具备回滚能力。',
 NULL, 'T', 5, 3, 1),

-- ---------- 题库：Spring（8 道） ----------
('Spring', 'single', '同一个类内部方法直接调用另一个带 @Transactional 的方法，事务为什么会失效？',
 '[{"key":"A","text":"因为 Spring 的事务只支持 public 方法"},{"key":"B","text":"因为自调用不经过代理对象，注解不会被拦截"},{"key":"C","text":"因为 @Transactional 只能用在 Controller 上"},{"key":"D","text":"因为数据库不支持嵌套事务"}]',
 'B', 5, 3, 1),

('Spring', 'single', 'Spring Bean 的默认作用域是？',
 '[{"key":"A","text":"prototype"},{"key":"B","text":"singleton"},{"key":"C","text":"request"},{"key":"D","text":"session"}]',
 'B', 5, 1, 1),

('Spring', 'single', '@RestController 等价于哪两个注解的组合？',
 '[{"key":"A","text":"@Controller 与 @ResponseBody"},{"key":"B","text":"@Service 与 @Transactional"},{"key":"C","text":"@Component 与 @Configuration"},{"key":"D","text":"@RequestMapping 与 @CrossOrigin"}]',
 'A', 5, 2, 1),

('Spring', 'single', 'Spring Boot 3 中自动配置类主要靠哪个文件登记？',
 '[{"key":"A","text":"META-INF/spring.factories"},{"key":"B","text":"META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports"},{"key":"C","text":"application.properties"},{"key":"D","text":"web.xml"}]',
 'B', 5, 4, 1),

('Spring', 'single', 'Spring 用三级缓存解决循环依赖，主要是为了避免什么？',
 '[{"key":"A","text":"重复创建同一个 Bean 导致无限递归"},{"key":"B","text":"数据库连接泄漏"},{"key":"C","text":"事务传播失效"},{"key":"D","text":"AOP 代理失效"}]',
 'A', 5, 4, 1),

('Spring', 'single', '关于 @Autowired 与 @Resource，下列说法正确的是？',
 '[{"key":"A","text":"两者完全等价"},{"key":"B","text":"@Autowired 默认按类型注入，@Resource 默认按名称注入"},{"key":"C","text":"@Resource 是 Spring 提供的注解"},{"key":"D","text":"@Autowired 只能用在字段上"}]',
 'B', 5, 3, 1),

('Spring', 'multiple', '下列哪些属于 Spring 的依赖注入方式？（多选）',
 '[{"key":"A","text":"构造器注入"},{"key":"B","text":"Setter 注入"},{"key":"C","text":"字段注入"},{"key":"D","text":"通过静态变量直接赋值"}]',
 'ABC', 5, 2, 1),

('Spring', 'judge', 'Spring 的构造器注入能保证依赖不为 null，因此比字段注入更容易发现配置问题。',
 NULL, 'T', 5, 2, 1),

-- ---------- 题库：计算机网络（8 道） ----------
('计算机网络', 'single', 'TCP 建立连接为什么需要三次握手而不是两次？',
 '[{"key":"A","text":"为了让双方都确认对方的收发能力正常，并避免历史连接被误建立"},{"key":"B","text":"因为三次握手能提高传输速度"},{"key":"C","text":"因为两次握手无法分配端口"},{"key":"D","text":"因为 TCP 需要先协商加密算法"}]',
 'A', 5, 3, 1),

('计算机网络', 'single', 'HTTPS 相比 HTTP 多了什么？',
 '[{"key":"A","text":"在传输层之上增加了 TLS，提供加密、完整性校验与身份认证"},{"key":"B","text":"只是把端口从 80 换成了 443"},{"key":"C","text":"压缩了请求体"},{"key":"D","text":"把 TCP 换成了 UDP"}]',
 'A', 5, 2, 1),

('计算机网络', 'single', 'HTTP/2 相比 HTTP/1.1 的关键改进是？',
 '[{"key":"A","text":"在一条连接上多路复用多个请求"},{"key":"B","text":"取消了请求头"},{"key":"C","text":"把文本协议改成了二进制之外的第三种编码"},{"key":"D","text":"强制要求使用 UDP"}]',
 'A', 5, 3, 1),

('计算机网络', 'single', 'TCP 与 UDP 的核心区别是？',
 '[{"key":"A","text":"TCP 面向连接且可靠有序，UDP 无连接、不保证到达与顺序"},{"key":"B","text":"UDP 比 TCP 更可靠"},{"key":"C","text":"TCP 不能传输大文件"},{"key":"D","text":"UDP 必须三次握手"}]',
 'A', 5, 1, 1),

('计算机网络', 'single', '在浏览器输入 URL 后，最先发生的一般是哪一步？',
 '[{"key":"A","text":"DNS 解析域名得到 IP 地址"},{"key":"B","text":"建立 TCP 连接"},{"key":"C","text":"发送 HTTP 请求"},{"key":"D","text":"渲染页面"}]',
 'A', 5, 2, 1),

('计算机网络', 'single', 'HTTP 状态码 401 与 403 的区别是？',
 '[{"key":"A","text":"401 表示未认证，403 表示已认证但无权限"},{"key":"B","text":"401 表示服务器错误，403 表示客户端错误"},{"key":"C","text":"两者完全等价"},{"key":"D","text":"401 表示资源不存在"}]',
 'A', 5, 2, 1),

('计算机网络', 'multiple', '下列哪些属于应用层协议？（多选）',
 '[{"key":"A","text":"HTTP"},{"key":"B","text":"DNS"},{"key":"C","text":"FTP"},{"key":"D","text":"TCP"}]',
 'ABC', 5, 2, 1),

('计算机网络', 'judge', 'HTTPS 能保证传输内容的机密性与完整性，但不能保证服务器一定不是钓鱼站点。',
 NULL, 'T', 5, 4, 1),

-- ---------- 题库：操作系统（6 道） ----------
('操作系统', 'single', '进程与线程最主要的区别是？',
 '[{"key":"A","text":"进程有独立地址空间，同一进程内的线程共享地址空间"},{"key":"B","text":"线程一定比进程慢"},{"key":"C","text":"进程不能并发执行"},{"key":"D","text":"线程不能访问堆内存"}]',
 'A', 5, 2, 1),

('操作系统', 'single', '虚拟内存的主要作用是？',
 '[{"key":"A","text":"让每个进程看到连续且独立的地址空间，并支持内存换出"},{"key":"B","text":"让 CPU 跑得更快"},{"key":"C","text":"彻底消除内存泄漏"},{"key":"D","text":"替代磁盘存储"}]',
 'A', 5, 2, 1),

('操作系统', 'single', '下列哪一项不是死锁的必要条件？',
 '[{"key":"A","text":"互斥"},{"key":"B","text":"占有并等待"},{"key":"C","text":"可抢占"},{"key":"D","text":"循环等待"}]',
 'C', 5, 3, 1),

('操作系统', 'single', '用户态切换到内核态通常由什么触发？',
 '[{"key":"A","text":"系统调用、中断或异常"},{"key":"B","text":"任意一条赋值语句"},{"key":"C","text":"变量声明"},{"key":"D","text":"函数返回"}]',
 'A', 5, 3, 1),

('操作系统', 'multiple', '死锁的四个必要条件包括哪些？（多选）',
 '[{"key":"A","text":"互斥"},{"key":"B","text":"占有并等待"},{"key":"C","text":"不可抢占"},{"key":"D","text":"循环等待"}]',
 'ABCD', 5, 3, 1),

('操作系统', 'judge', '页表的作用是把虚拟地址映射到物理地址。',
 NULL, 'T', 5, 2, 1),

-- ---------- 题库：数据结构（6 道） ----------
('数据结构', 'single', '快速排序在什么情况下退化为 O(n^2)？',
 '[{"key":"A","text":"每次选到的基准都把序列分成极不均衡的两部分"},{"key":"B","text":"序列完全随机时"},{"key":"C","text":"序列长度小于 10 时"},{"key":"D","text":"使用三数取中选基准时"}]',
 'A', 5, 3, 1),

('数据结构', 'single', 'B+ 树比二叉树更适合做磁盘索引，主要原因是？',
 '[{"key":"A","text":"单个节点能存更多键，树更矮，磁盘 IO 次数更少"},{"key":"B","text":"B+ 树不支持范围查询"},{"key":"C","text":"B+ 树不需要排序"},{"key":"D","text":"B+ 树占用的内存更少"}]',
 'A', 5, 3, 1),

('数据结构', 'single', '哈希表解决冲突的常见方法不包括下列哪一项？',
 '[{"key":"A","text":"链地址法"},{"key":"B","text":"开放定址法"},{"key":"C","text":"再哈希法"},{"key":"D","text":"中序遍历法"}]',
 'D', 5, 2, 1),

('数据结构', 'single', '栈与队列的主要区别是？',
 '[{"key":"A","text":"栈后进先出，队列先进先出"},{"key":"B","text":"栈先进先出，队列后进先出"},{"key":"C","text":"两者完全等价"},{"key":"D","text":"队列不能存对象"}]',
 'A', 5, 1, 1),

('数据结构', 'multiple', '下列关于红黑树的说法正确的有？（多选）',
 '[{"key":"A","text":"它是一种自平衡二叉查找树"},{"key":"B","text":"查找、插入、删除的最坏时间复杂度都是 O(log n)"},{"key":"C","text":"通过旋转与变色维持平衡"},{"key":"D","text":"它的每个节点必须是红色"}]',
 'ABC', 5, 3, 1),

('数据结构', 'judge', '在最坏情况下，哈希表的查找时间复杂度会退化到 O(n)。',
 NULL, 'T', 5, 2, 1)
ON DUPLICATE KEY UPDATE stem = VALUES(stem);

-- ---------- 考试 ----------
-- 时间窗用相对时间：昨天开考、7 天后关闭，任何时候执行种子脚本都能直接考
INSERT INTO exam (title, subject, start_at, end_at, duration_minutes, total_score, pass_score, status, created_by)
VALUES ('Java 后端基础月考（第 1 期）', 'Java',
        NOW() - INTERVAL 1 DAY, NOW() + INTERVAL 7 DAY,
        60, 100, 60, 'published', 1)
ON DUPLICATE KEY UPDATE title = VALUES(title);

-- ---------- 组卷 ----------
-- 只把前 10 道放进这场考试：题库现在有 68 道，但演示卷固定 10 题 100 分，
-- e2e 脚本手算的 87 分依赖这个固定卷面。q.id <= 10 之所以可靠，
-- 是因为上面刚把 question 的 AUTO_INCREMENT 复位成 1，前 10 条就是 id 1–10。
-- 分值写在关联表上：同一道题换个考试可以重新定权重
INSERT INTO exam_question (exam_id, question_id, score, seq)
SELECT e.id, q.id,
       CASE WHEN q.type = 'single' THEN 10 WHEN q.type = 'multiple' THEN 15 ELSE 5 END,
       q.id
FROM exam e JOIN question q
WHERE e.title = 'Java 后端基础月考（第 1 期）'
  AND q.id <= 10
ON DUPLICATE KEY UPDATE score = VALUES(score);
