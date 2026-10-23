# online-exam 技术栈完整知识点

> 按「这个项目真正用到的」组织，不是通用八股清单。每个知识点：**是什么 → 为什么重要 → 面试怎么答**。
>
> 通用八股补漏（集合框架 / String / 泛型 / 反射 / Stream / 异常 / AQS / 虚拟线程）在 `STUDY-JAVA-GAPS.md` —— 那份补的是「面试官一定会问、但项目里用不上」的部分。

## 目录

| 章 | 主题 | 重要度 |
|---|---|---|
| 1 | 项目全景（架构与请求链路） | ★★★ |
| 2 | Java 17 语言特性 | ★★ |
| 3 | Spring Boot 3 | ★★★ |
| 4 | Spring Security + JWT | ★★★ |
| 5 | MyBatis-Plus | ★★ |
| 6 | MySQL 8.0 | ★★★ |
| 7 | Redis | ★★★ |
| 8 | RabbitMQ | ★★★ |
| 9 | WebSocket | ★★ |
| 10 | 并发与线程 | ★★★ |
| 11 | JVM 基础 | ★★ |
| 12 | Flyway 数据库迁移 | ★★ |
| 13 | Docker | ★★ |
| 14 | 监控（Micrometer + Prometheus） | ★ |
| 15 | 测试（JUnit 5 + Mockito） | ★★ |
| 16 | 项目中的五个核心设计 | ★★★ |

---

# 第 1 章 项目全景

## 1.1 系统做什么

老师出题组卷 → 学生进入考试 → 限时答题 → 交卷 → 自动判分 → 出成绩/统计。

## 1.2 技术栈全景

```
浏览器
  │ HTTPS + JWT
  ▼
Spring Boot 3.3.4（Java 17）
  ├─ Spring Security 6 ── 认证鉴权，JWT 校验，Redis 黑名单
  ├─ Controller ────────── 参数校验、统一响应、全局异常
  ├─ Service ───────────── 业务规则（判分、会话、状态机）
  ├─ MyBatis-Plus ──────── 数据访问
  ├─ Redis ─────────────── 卷面缓存、JWT 黑名单、在线人数
  ├─ RabbitMQ ──────────── 异步判分
  ├─ WebSocket ─────────── 倒计时推送
  └─ 定时任务 ──────────── 自动收卷、判分补偿扫描
  ▼
MySQL 8.0（Flyway 管理表结构）
```

## 1.3 一次「学生交卷」的完整链路（必须能背）

```
1. POST /api/student/exams/{id}/submit （带 JWT）
2. Spring Security 过滤器：解析 JWT → 查 Redis 黑名单 → 放行
3. Controller 参数校验 → 调用 ExamSessionService.submit()
4. 业务校验：考试窗口、状态是否已交（带条件的 UPDATE 保证幂等）
5. 写 record 状态 = SUBMITTED，分数置空
6. 尝试发 MQ 消息（异步判分）
   ├─ 发送成功 → 立即返回「已交卷，分数待定」
   └─ 发送失败 → 降级成同步判分，直接返回分数
7. 消费者收到消息 → GradingService.grade()
   ├─ 判分 → 写回分数
   └─ 消费者不抛异常回 Broker（避免无限重投）
8. 定时任务每 30 秒扫描「已交卷但无分数」→ 写指标告警
```

**这张图是面试的主线，讲项目就从它开始。**

---

# 第 2 章 Java 17 语言特性

## 2.1 record（记录类）

**是什么**：不可变数据载体，自动生成构造器、getter、equals/hashCode/toString。

```java
public record Result(boolean correct, int scoreGot) {}
public record ApiResponse<T>(int code, String message, T data) {}
```

**关键点**：字段隐式 private final，天然不可变；适合 DTO、返回值、多值返回；是 final 不能继承。

**面试答法**：
> 判分结果用 record，因为它天然不可变 —— correct 和 scoreGot 一旦产生就不该被改。

## 2.2 switch 表达式（箭头语法）

```java
HttpStatus status = switch (e.code()) {
    case 401 -> HttpStatus.UNAUTHORIZED;
    case 403 -> HttpStatus.FORBIDDEN;
    case 404 -> HttpStatus.NOT_FOUND;
    default  -> HttpStatus.BAD_REQUEST;
};
```

**关键点**：箭头不会贯穿（不需要 break）；作为表达式有返回值；**枚举缺分支编译报错**（穷尽性检查）。

## 2.3 原生枚举 + 自定义 code

```java
public enum QuestionType {
    SINGLE(1), MULTIPLE(2), JUDGE(3);
    private final int code;
}
```

**关键点**：枚举**按 code 入库**，不是 ordinal。
**为什么不能用 ordinal**：ordinal 是声明顺序，删一个/加一个导致顺序变，历史数据全错。

## 2.4 其他常见特性

| 特性 | 说明 |
|---|---|
| var | 局部变量类型推断（Java 10+），不能用于字段/方法参数 |
| List.of() | 不可变集合，add 会抛 UnsupportedOperationException |
| Optional | 显式表达「可能没有」，不要用作字段和参数 |
| 文本块 | 多行字符串 |
| String.isBlank() | 判空白，比 trim().isEmpty() 清晰 |

---

# 第 3 章 Spring Boot 3

## 3.1 IoC（控制反转）

**是什么**：对象的创建、依赖装配交给容器，而不是自己 new。

**为什么**：解耦、可替换（测试换 mock）、单例复用。

| 注入方式 | 说明 | 推荐 |
|---|---|---|
| 字段注入 @Autowired | 简单，但测试不好替换 | ❌ |
| setter 注入 | 灵活 | 少用 |
| **构造器注入** | 依赖不可变，缺依赖启动就报错 | ✅ |

**面试答法**：
> 我用构造器注入。字段注入没法在测试里传假的进去，而且它允许写出循环依赖 —— 构造器注入循环依赖直接启动失败，问题当场暴露。

## 3.2 AOP（面向切面）

**是什么**：把横切关注点（日志、事务、权限、监控）抽出来，用**动态代理**在方法前后织入。

| 方式 | 条件 |
|---|---|
| JDK 动态代理 | 目标类实现了接口 |
| CGLIB | 没有接口（生成子类） |

**Spring Boot 2.x 起默认 CGLIB。**

**面试答法**：
> AOP 是动态代理。Spring Boot 默认 CGLIB，所以即使没接口也能代理。@Transactional 能生效的前提是调用经过代理 —— 这就是同类内部调用事务失效的原因。

## 3.3 Spring Boot 自动装配

```
@SpringBootApplication
  └─ @EnableAutoConfiguration
       └─ 读取 META-INF/spring/...AutoConfiguration.imports
            └─ 加载候选配置类
                 └─ @ConditionalOnClass / @ConditionalOnMissingBean / @ConditionalOnProperty
                      └─ 按需生效
```

**核心是条件注解**：引入了依赖才装配。

**面试答法**：
> 自动装配是「约定优于配置」。启动时扫描所有 jar 里的自动配置声明，再用条件注解筛选 —— 引入什么依赖就装配什么功能。想覆盖，自己定义同类型 Bean，@ConditionalOnMissingBean 就不生效了。

## 3.4 Bean 生命周期

```
实例化 → 属性注入 → Aware 回调 → BeanPostProcessor 前置
       → @PostConstruct → InitializingBean → 自定义 init
       → BeanPostProcessor 后置（★ AOP 代理在这一步生成）
       → 使用 → @PreDestroy → DisposableBean → 销毁
```

**面试答法**：
> 关键点是 AOP 代理在 BeanPostProcessor 后置阶段生成，所以在 @PostConstruct 里调用自己的事务方法，代理还没包上，事务不生效。

## 3.5 循环依赖与三级缓存

| 级别 | 内容 | 作用 |
|---|---|---|
| 一级 singletonObjects | 完整的 Bean | 成品 |
| 二级 earlySingletonObjects | 提前暴露的原始 Bean | 半成品 |
| 三级 singletonFactories | ObjectFactory | 能生成代理的工厂 |

**为什么要三级不是两级**：三级存**工厂**，可以在需要时决定是否生成 AOP 代理。

**解决不了**：构造器注入的循环依赖（对象还没造出来）、prototype 作用域。

**面试答法**：
> 三级缓存的核心是提前暴露未初始化完成的引用。只能解决 setter/字段注入，构造器注入的解决不了。

## 3.6 事务 @Transactional

**七个传播行为**（常问前三个）：

| 传播行为 | 说明 |
|---|---|
| REQUIRED（默认） | 有事务就加入，没有就新建 |
| REQUIRES_NEW | 总是新建，挂起外层 |
| NESTED | 嵌套，用 savepoint |
| SUPPORTS | 有就加入，没有就非事务运行 |
| NOT_SUPPORTED | 非事务运行，挂起外层 |
| MANDATORY | 必须有事务，否则抛异常 |
| NEVER | 必须没有事务，否则抛异常 |

**失效的四种情况（必背）**：
1. **同类内部调用** —— 不走代理
2. **方法不是 public** —— 代理拦截不到
3. **异常被自己 catch 了** —— 代理收不到
4. **抛受检异常** —— 默认只回滚 RuntimeException 和 Error

**面试答法**：
> @Transactional 靠 AOP 代理，失效最常见的是同类内部调用 —— this.method() 绕过了代理。另外默认只回滚 RuntimeException。

## 3.7 Spring MVC 请求流程

```
请求 → DispatcherServlet
     → HandlerMapping（找到 Controller 方法）
     → HandlerAdapter（参数解析、类型转换）
     → 拦截器 preHandle → Controller → postHandle
     → 消息转换器（对象转 JSON）→ 响应
```

@ControllerAdvice + @ExceptionHandler 做全局异常处理，本项目用它统一转成 ApiResponse。

---


# 第 4 章 Spring Security + JWT

## 4.1 JWT 结构

```
header.payload.signature
  │       │         └─ HMAC-SHA256(base64(header) + "." + base64(payload), secret)
  │       └─ 声明：sub（用户）、exp（过期）、jti（唯一 id）
  └─ 算法：{"alg":"HS256"}
```

**关键点**：
- **payload 只是 base64，不是加密** —— 任何人都能解出来，不要放敏感信息
- 签名保证**不被篡改**，但不管「能不能用」
- exp 只是声明，**服务端必须校验**

## 4.2 为什么 JWT 还要配 Redis（必问）

**JWT 是无状态的** —— 服务端不存 session，所以**没法主动失效**。

| 问题 | 做法 |
|---|---|
| **主动注销 / 强制下线** | 存 jti 黑名单，TTL = token 剩余有效期 |
| **Refresh Token 管理** | 存 Redis，支持轮换和多设备 |
| **令牌版本** | 改密码后版本 +1，旧 token 全部失效 |

**面试答法**：
> JWT 的优点是无状态、不查库，代价是没法主动失效 —— 它做不到「用户点了退出，这个 token 立刻不能用」。解法是把 jti 存 Redis 黑名单，校验时多查一次 Redis。这一步等于把无状态又变回有状态了，所以只在需要主动失效的场景才做。另一个办法是存「令牌版本」，改密码时版本 +1，比逐条黑名单省内存。

## 4.3 Spring Security 6 过滤器链

```
请求 → SecurityFilterChain
  ├─ JwtAuthenticationFilter（自定义，在 UsernamePasswordAuthenticationFilter 之前）
  │    ├─ 从 Authorization 头取 Bearer token
  │    ├─ 解析并校验签名/过期
  │    ├─ 查 Redis 黑名单
  │    └─ 放进 SecurityContext
  ├─ 授权（hasRole / permitAll）
  └─ 放行或 401/403
```

**关键点**：
- **401 = 未认证**（不知道你是谁）
- **403 = 未授权**（知道你是谁，但没权限）
- **SecurityContext 是 ThreadLocal** —— 异步线程里拿不到，需要传递

## 4.4 密码存储

**必须用 BCryptPasswordEncoder**：
- 自带**随机盐**，同一个密码每次哈希都不同
- 故意**慢**（可调强度），抵抗暴力破解
- **不能用 MD5/SHA** —— 太快，显卡一秒能算几十亿次

---

# 第 5 章 MyBatis-Plus

## 5.1 它是什么

MyBatis 的增强：单表 CRUD、分页、条件构造器、乐观锁、逻辑删除都不用写 SQL。

**关系**：MyBatis-Plus 不是替代 MyBatis，是在它上面加了一层。复杂 SQL 还是写 XML 或 @Select，简单的用 BaseMapper。

## 5.2 驼峰映射（一个真实的坑）

**默认** map-underscore-to-camel-case: true，deadline_at 自动映射到 deadlineAt。

**坑**：用 @Select 返回 Map 时，**列名不会自动转驼峰**，拿到的是 deadline_at。
> 这个坑我在项目里踩过。解法是给列加别名 AS deadlineAt，或配 mapUnderscoreToCamelCase。

## 5.3 分页插件

```java
Page<Record> page = new Page<>(pageNum, pageSize);
recordMapper.selectPage(page, wrapper);
```

**必须注册 MybatisPlusInterceptor + PaginationInnerInterceptor**，否则分页不生效（查全表）。

## 5.4 乐观锁

@Version 注解 + 版本号字段：更新时 WHERE version = ?，影响行数为 0 说明被别人改过。

**和本项目的幂等思路一致** —— 都是**用影响行数判断竞争结果**。

---

# 第 6 章 MySQL 8.0

## 6.1 InnoDB 索引为什么是 B+ 树

| 对比 | 说明 |
|---|---|
| B 树 | 每个节点都存数据 → 节点能存的 key 少 → 树更高 → 磁盘 IO 多 |
| **B+ 树** | 只有叶子存数据，非叶子只存索引 → 一页存更多 key → 树矮（3-4 层撑千万级） |
| 哈希 | 等值查询 O(1)，但**不支持范围查询和排序** |
| 红黑树 | 二叉，树高太大，磁盘 IO 不可接受 |

**两个优势**：① 矮胖，3-4 次 IO 能查到 ② 叶子节点是链表，范围查询和排序顺。

## 6.2 聚簇索引 vs 二级索引

| | 聚簇索引（主键） | 二级索引 |
|---|---|---|
| 叶子存什么 | **整行数据** | **主键值** |
| 一张表有几个 | 1 个 | 多个 |

**回表**：走二级索引查到主键，再拿主键去聚簇索引查整行 → 两次查找。

**覆盖索引**：查询的列全在索引里，不用回表。EXPLAIN 里 Extra: Using index 就是。

## 6.3 唯一索引（本项目幂等的基石）★

```sql
ALTER TABLE record ADD UNIQUE KEY uk_user_exam (user_id, exam_id);
```

**为什么它能做幂等**：
- 并发下**只有一个插入能成功**，其余抛 DuplicateKeyException
- **不需要先查再插** —— 先查再插有并发窗口（两个请求同时查到「不存在」都去插）

**代价**：写入时要检查唯一性，略慢，但**远小于加锁**。

## 6.4 事务隔离级别与 MVCC

| 级别 | 脏读 | 不可重复读 | 幻读 |
|---|---|---|---|
| 读未提交 | ✅ | ✅ | ✅ |
| **读已提交（RC）** | ❌ | ✅ | ✅ |
| **可重复读（RR）MySQL 默认** | ❌ | ❌ | ❌（快照读下） |
| 串行化 | ❌ | ❌ | ❌ |

**MVCC 实现**：

```
隐藏字段：DB_TRX_ID（最后修改的事务 id）、DB_ROLL_PTR（指向 undo log）
ReadView：记录当前活跃事务 id 列表
可见性判断：
  trx_id < min_trx_id   → 可见（已提交）
  trx_id >= max_trx_id  → 不可见（之后才开始）
  min <= trx_id < max   → 看是否在活跃列表里，在就不可见
```

**面试答法**：
> MVCC 让读不加锁，读写不互相阻塞。每行有隐藏的事务 id 和 undo 指针，读的时候生成 ReadView，按规则判断这一行哪个版本对我可见。这样就做到了可重复读：事务开始时拍一张快照，之后都看快照。

## 6.5 行锁与间隙锁

| 锁 | 触发条件 |
|---|---|
| 记录锁 | 唯一索引等值命中 |
| 间隙锁 | 可重复读下，范围查询锁住间隙 |
| 临键锁 | 记录锁 + 间隙锁 |

**关键**：唯一索引等值查询且**存在** → 退化成记录锁；**不存在** → 间隙锁（防止别人插入）。

## 6.6 深分页问题

**问题**：LIMIT 1000000, 10 要扫描并丢弃前 100 万行。

```sql
-- ① 延迟关联：先用覆盖索引查主键，再回表
SELECT * FROM t
JOIN (SELECT id FROM t ORDER BY id LIMIT 1000000, 10) x USING(id);

-- ② 游标分页：记住上一页最大 id
SELECT * FROM t WHERE id > 1000000 ORDER BY id LIMIT 10;
```

**注意**：游标分页**不能跳页**，只适合「下一页」。

## 6.7 索引失效场景（必背）

1. **左模糊** LIKE '%abc'
2. **对列做函数或运算** WHERE YEAR(created_at) = 2026
3. **隐式类型转换**：字符串列写数字 WHERE phone = 13800138000
4. **联合索引不满足最左前缀**
5. **OR 连接非索引列**
6. != / NOT IN 有时失效（优化器判断全表更快）

## 6.8 EXPLAIN 看什么

| 列 | 关注点 |
|---|---|
| type | 至少要 range，最好 ref/eq_ref/const；ALL = 全表扫描 |
| key | 实际用的索引，NULL 就是没走索引 |
| rows | 预估扫描行数，越小越好 |
| Extra | Using index（覆盖索引，好）、Using filesort（要排序）、Using temporary（临时表） |


---

# 第 7 章 Redis

## 7.1 五种基本数据结构 + 用途

| 类型 | 用途举例 |
|---|---|
| String | 缓存 JSON、计数器、分布式锁 |
| Hash | 存对象的部分字段 |
| List | 简易消息队列、最新列表 |
| Set | 去重、共同好友 |
| **ZSet** | 排行榜、延迟队列（score 存时间戳） |

## 7.2 缓存三大问题（本项目逐个处理了）★

### 穿透
**问题**：查一个**不存在**的数据，缓存永不命中，每次都打库。
**解法**：**空值也缓存**（存哨兵值，短 TTL）—— 本项目用 `__NULL__`；或布隆过滤器。
**注意**：哨兵**不能用空字符串** —— 部分客户端会把空串和「不存在」搞混。

### 击穿
**问题**：**一个热点 key 过期**的瞬间，大量请求同时发现没缓存，同时去重建。
**解法**：**重建前抢锁**，只让一个去查库。

### 雪崩
**问题**：**一批 key 同一秒过期**，流量同时砸库。
**解法**：**TTL 加随机抖动**（本项目 ±20%），把过期时刻打散。

**面试答法**：
> 这三个是不同的问题：穿透是查不存在的数据，击穿是一个热点 key 过期，雪崩是一批 key 同时过期。解法分别是空值缓存 / 抢锁重建 / TTL 抖动。很多人把击穿和雪崩搞混 —— 击穿是单个热点，雪崩是批量。

## 7.3 分布式锁

```java
// 加锁：SET key value NX PX 30000
// 释放：必须用 Lua 保证原子
if redis.call('get', KEYS[1]) == ARGV[1] then
  return redis.call('del', KEYS[1])
else
  return 0
end
```

**为什么要 Lua**：**判断是不是自己的锁** 和 **删除** 必须原子。
**不原子的后果**：判断完 → 锁刚好过期 → 别人抢到 → 你把人家的锁删了。

**经典缺陷**：锁过期但业务没跑完（需要看门狗续期，Redisson）；主从切换丢锁（RedLock，有争议）。

## 7.4 Redis 单线程为什么快

1. 纯内存操作
2. IO 多路复用（epoll）
3. 避免多线程上下文切换和锁竞争

**注意（容易说错）**：**Redis 6.0 之后网络 IO 是多线程的，但命令执行仍是单线程。**

## 7.5 过期与淘汰

**过期策略**：惰性删除（访问时才检查）+ 定期删除（每 100ms 随机抽查）。

| 淘汰策略 | 说明 |
|---|---|
| noeviction | 不淘汰，写入报错（默认） |
| allkeys-lru | 所有 key 里淘汰最久未用 |
| allkeys-lfu | 淘汰访问频率最低 |
| volatile-lru | 只在**设了过期时间**的 key 里淘汰 |
| allkeys-random | 随机淘汰 |

## 7.6 持久化

| | RDB | AOF |
|---|---|---|
| 原理 | 快照 | 追加写命令 |
| 恢复速度 | 快 | 慢 |
| 数据安全 | 可能丢一段 | 更安全 |
| 文件大小 | 小 | 大 |

**生产常用：RDB + AOF 混合。**

---

# 第 8 章 RabbitMQ

## 8.1 核心概念

```
Producer → Exchange → (Binding + Routing Key) → Queue → Consumer
```

| Exchange 类型 | 路由规则 |
|---|---|
| direct | routing key 完全匹配 |
| topic | 通配符（* 一个词，# 零或多个词） |
| fanout | 广播，忽略 routing key |
| headers | 按 header 匹配 |

## 8.2 怎么保证消息不丢（必背）

| 环节 | 做法 |
|---|---|
| 生产者 → Broker | **confirm 确认**（异步回调）/ 事务（性能差） |
| Broker 内部 | Exchange、Queue、Message 都**持久化** |
| Broker → 消费者 | **手动 ACK**，处理完再确认 |

**关键**：autoAck = true 有丢消息风险（消息一投递就删）；**三个环节缺一个都不算不丢**。

## 8.3 怎么保证不重复消费

**MQ 只能保证「至少一次」**，所以消费端**必须幂等**。

1. **唯一键 + 唯一索引**（插入失败就是重复）
2. **状态机 + 带条件的 UPDATE**：WHERE status = 'x'，影响行数 0 就是重复
3. Redis 记录已处理的消息 id（注意 TTL 和原子性）

**本项目**：markGraded() 返回 rows == 0 就抛 AlreadyGradedException。

## 8.4 消费者为什么不能把异常抛回 Broker ★

**如果抛异常**：消息重新入队 → **立刻重投** → 无限循环 → 打满 CPU。

**正确做法**：捕获异常记日志 + 指标；需要重试走死信队列 + 延迟重试。

**面试答法**：
> 消费者不能把异常抛回 Broker —— 会无限重投。我的做法是捕获后记指标，另外起一个定时任务扫描「已交卷但没分数」的记录。**失败不会自愈，但它不再是静默的。**

## 8.5 死信队列

消息变死信的三种情况：① 被拒绝且 requeue = false ② 过期（TTL 到期）③ 队列满。
**用途**：延迟任务、失败兜底、人工排查。

## 8.6 prefetch（预取数量）

控制消费者一次拿多少条。太大 → 一个消费者拿太多，别的闲着；太小 → 吞吐低。

---

# 第 9 章 WebSocket

## 9.1 和 HTTP 的关系

**WebSocket 用 HTTP 完成握手，之后升级成独立协议。**

```
客户端：GET /ws HTTP/1.1
        Upgrade: websocket
        Connection: Upgrade
        Sec-WebSocket-Key: xxx

服务端：HTTP/1.1 101 Switching Protocols  ← 之后就不是 HTTP 了
```

## 9.2 WebSocket vs SSE vs 轮询

| | WebSocket | SSE | 轮询 |
|---|---|---|---|
| 方向 | **双向** | 单向（服务端→客户端） | 客户端拉 |
| 协议 | 独立协议 ws:// | 就是 HTTP | HTTP |
| 自动重连 | 要自己写 | **浏览器内置** | — |
| 适合 | 聊天、协作、游戏 | 通知、进度、**倒计时** | 简单场景 |

**面试答法**：
> 倒计时其实 SSE 更合适 —— 它是单向推送，浏览器还自带重连。我用 WebSocket 是因为项目里还要收客户端的操作（比如提前交卷），需要双向。如果只是推时间，SSE 更省事。

## 9.3 关键设计点

**① 时间以服务端为准**
> 剩余时间从库里的 deadline_at 算，不接受客户端传的任何时间 —— 改本地时间无效。

**② 握手要认证**
> WebSocket 握手是 HTTP 请求，可以带 token。用 HandshakeInterceptor 校验。**注意浏览器 WebSocket API 不支持自定义头**，token 要放在 query 参数或子协议里。

**③ 水平扩展问题（必问）**
> **现在做不到。** 连接存在单机内存里，多实例部署时用户连在 A 机器、推送在 B 机器，推不到。解法是广播（Redis Pub/Sub 或消息队列）。另外倒计时里有 synchronized (session) 是单机锁，集群里没意义。

---


# 第 10 章 并发与线程

## 10.1 线程池七个参数（必背）

```java
new ThreadPoolExecutor(
    corePoolSize,      // 核心线程数
    maximumPoolSize,   // 最大线程数
    keepAliveTime,     // 非核心线程空闲存活时间
    unit,
    workQueue,         // 任务队列
    threadFactory,     // 线程工厂（起名字，方便排查）
    handler            // 拒绝策略
);
```

**执行顺序（必背）**：

```
① 核心线程没满 → 建核心线程
② 核心线程满了 → 进队列
③ 队列满了 → 建非核心线程（到 maximumPoolSize）
④ 也满了 → 拒绝策略
```

**★ 关键**：**队列在最大线程之前**。所以用**无界队列**（LinkedBlockingQueue 默认容量 Integer.MAX_VALUE）会导致 maximumPoolSize **永远不生效** —— 队列永远不满。

| 拒绝策略 | 行为 |
|---|---|
| AbortPolicy（默认） | 抛 RejectedExecutionException |
| CallerRunsPolicy | **调用者线程执行**（降速，但不丢任务） |
| DiscardPolicy | 静默丢弃 |
| DiscardOldestPolicy | 丢最老的，塞新的 |

## 10.2 synchronized 与 ReentrantLock

| | synchronized | ReentrantLock |
|---|---|---|
| 层面 | JVM 关键字 | JDK API |
| 释放 | **自动** | **手动**（必须 finally） |
| 可中断 | ❌ | ✅ lockInterruptibly |
| 超时 | ❌ | ✅ tryLock(timeout) |
| 公平锁 | ❌ | ✅ 可配 |
| 条件变量 | 一个 | 多个 Condition |

**synchronized 锁升级**：无锁 → 偏向锁 → 轻量级锁（自旋）→ 重量级锁。

## 10.3 CAS

**Compare And Swap**：只有当前值等于期望值才更新。底层是 CPU 的 cmpxchg 指令。

```java
do {
    old = get();
} while (!compareAndSwap(old, newValue));
```

**三个问题**：
1. **ABA** —— 用 AtomicStampedReference（加版本号）
2. **自旋消耗 CPU** —— 竞争激烈时不如加锁
3. **只能保证一个变量** —— 多变量用 AtomicReference 包对象

## 10.4 volatile

**两个作用**：可见性（一个线程改了，其他立刻看到）、禁止指令重排（内存屏障）。
**不保证原子性** —— i++ 依然不安全（读-改-写三步）。
**经典用途**：双重检查锁的单例。

## 10.5 内存模型（JMM）

**主内存 + 工作内存**：每个线程有自己的工作内存（CPU 缓存）。

**happens-before 规则（记住前三条）**：
1. 程序顺序：同一线程内，前面的写对后面可见
2. 监视器锁：解锁先于后续加锁
3. volatile：写先于后续读

---

# 第 11 章 JVM 基础

## 11.1 内存区域

| 区域 | 说明 | 线程 |
|---|---|---|
| **堆** | 对象实例 | 共享 |
| **方法区 / 元空间** | 类信息、常量、静态变量 | 共享 |
| 虚拟机栈 | 栈帧（局部变量、操作数栈） | 私有 |
| 本地方法栈 | native 方法 | 私有 |
| 程序计数器 | 当前字节码行号 | 私有 |

**元空间（Metaspace）在本地内存**，不再是永久代。

## 11.2 垃圾回收

**怎么判断对象已死**：**可达性分析**（从 GC Roots 出发，不可达就是垃圾）。
**GC Roots**：栈上的局部变量、静态变量、常量、native 引用。

```
新生代（Eden + S0 + S1）—— 复制算法，频繁 Minor GC
老年代 —— 标记-整理 / 标记-清除，慢，Major GC
```

**对象什么时候进老年代**：① 年龄到阈值（默认 15）② 大对象直接进 ③ 动态年龄判定（Survivor 中同龄对象总和超过一半）。

## 11.3 双亲委派

**加载类时先让父加载器加载，父加载不了才自己加载。**

**好处**：避免重复加载；**安全**（防止自定义 java.lang.String 覆盖核心类）。

**三个类加载器**：Bootstrap → Extension/Platform → Application。

---

# 第 12 章 Flyway

## 12.1 是什么

数据库版本管理：把建表/改表写成带版本号的 SQL，启动时自动按顺序执行未执行过的。

## 12.2 命名规则

```
V<版本号>__<描述>.sql    版本化迁移（执行一次）
R__<描述>.sql             可重复执行（校验和变了就重跑）
U<版本号>__<描述>.sql     撤销（社区版不支持）
```

**两个下划线**是分隔符，别写错。

## 12.3 校验和（一个铁律）★

**Flyway 会记录每个迁移文件的 MD5。已经执行过的文件，一个字节都不能改 —— 包括注释。**
改了下次启动直接报 `Validate failed: checksum mismatch`。

**要改怎么办**：新建 V3__fix_xxx.sql，不要改 V1。

## 12.4 幂等写法

**面试答法**：
> 迁移文件必须可重入。我的 V2 是加索引，在已经加过的库上重跑会报错，所以写成了先查 information_schema 再决定要不要建。这是实测跑出来的 —— 第一版直接在已有库上启动就失败了。

---

# 第 13 章 Docker

## 13.1 镜像分层

**每条 RUN/COPY 是一层，层会被缓存。** 所以：不常变的放前面（依赖），常变的放后面（源码）。

```dockerfile
# ✅ 好：先拷 pom，下载依赖（这层能缓存）
COPY pom.xml .
RUN mvn dependency:go-offline
# ❌ 差：先拷全部源码，改一行就重装依赖
COPY . .
```

## 13.2 多阶段构建

```dockerfile
FROM maven:3.9-eclipse-temurin-17 AS build
COPY pom.xml .
RUN mvn dependency:go-offline
COPY src ./src
RUN mvn package -DskipTests

FROM eclipse-temurin:17-jre
COPY --from=build /app/target/*.jar app.jar
ENTRYPOINT ["java","-jar","/app.jar"]
```

**好处**：最终镜像不含 Maven 和源码，体积从 ~700MB 降到 ~200MB。

## 13.3 depends_on 的坑（本项目踩过）★

**depends_on 只保证「启动顺序」，不保证「服务就绪」。**

**MySQL 官方镜像首次启动会先起一个临时实例**（只监听 unix socket，port 显示 0），初始化完才起正式实例（port 3306）。

**判断就绪不能只看 ping** —— 临时实例也会回 ping。

```bash
# ❌ 可能被临时实例骗过
docker exec exam-mysql mysqladmin ping

# ✅ 强制走 TCP，临时实例没有 TCP
docker exec exam-mysql mysqladmin -h 127.0.0.1 -uroot -proot ping --silent
```

**这是面试里很好的素材**：它证明你知道「进程活着」和「服务可用」不是一回事。

---

# 第 14 章 监控

## 14.1 链路

```
应用（Micrometer 埋点） → /actuator/prometheus 暴露文本 → Prometheus 定时抓 → Grafana 展示
```

## 14.2 四种指标类型

| 类型 | 说明 | 例子 |
|---|---|---|
| **Counter** | 只增不减 | 请求总数 |
| **Gauge** | 可增可减 | 当前在线人数、未判分数 |
| **Histogram** | 分布（分桶） | 请求耗时 p50/p99 |
| **Summary** | 分位数（客户端算） | 同上，但聚合困难 |

**注意**：Histogram 能在 Prometheus 端**跨实例聚合**，Summary 不能。做 p99 用 Histogram。

## 14.3 本项目的指标

| 指标 | 类型 | 意义 |
|---|---|---|
| exam_grading_unscored | Gauge | **已交卷但没分数的记录数** —— 判分补偿的告警线 |
| HTTP 请求耗时 | Histogram | p50/p99 |

**注意**：/actuator/prometheus 返回**纯文本不是 JSON**；默认只暴露 health/info，其余要显式打开；**/actuator/health 是框架自带的**，不是自己写的接口。

---

# 第 15 章 测试

## 15.1 JUnit 5 常用注解

| 注解 | 说明 |
|---|---|
| @Test | 测试方法 |
| @BeforeEach / @AfterEach | 每个测试前后 |
| @BeforeAll / @AfterAll | 类级别（**必须 static**） |
| @DisplayName | 可读的名字 |
| @ParameterizedTest + @CsvSource | 参数化 |
| @Nested | 分组 |

## 15.2 断言

```java
assertEquals(expected, actual);
assertThrows(BusinessException.class, () -> service.doX());
assertAll(...);  // 全部执行，不因第一个失败中断
```

## 15.3 Mockito

```java
@Mock UserMapper mapper;
@InjectMocks ExamService service;

when(mapper.selectById(1L)).thenReturn(user);
verify(mapper, times(1)).selectById(1L);
```

## 15.4 一个测试理念（本项目实践）★

**每个测试都要能证明它抓得住 bug**：
> 写完测试后，我故意把被测代码改错，看它是不是真的红。如果改错了它还是绿的，那这个测试就是摆设。

**这是加分的说法** —— 大部分人说不出这句。

---


# 第 16 章 项目中的五个核心设计（面试最值钱的部分）

前面 15 章是**通用知识**，这一章是**只有做过这个项目才知道的东西**。
面试官区分「背过八股」和「真的做过」，靠的就是这一章。

## 16.1 判分逻辑写成纯函数 ★★★

**代码里的注释原话**：
> 写成纯函数是为了能被完整单测 —— 判分算错是这类系统里最不可接受的 bug，而纯函数是唯一能被穷举验证的形态。

**为什么重要**：
- 纯函数 = 输入定输出，没有 IO、没有随机、没有时间依赖
- 所以可以**穷举所有组合**测试，不需要启动 Spring、不需要连数据库
- 判分错了是「学生分数错」——这是产品的根本性错误

**多选题的判分规则（`grading/GradingRule.java`，106 行，四个分支）**：

| 情况 | 得分 | correct |
|---|---|---|
| 选错（含错选项） | **0** | false |
| 完全正确 | 满分 | true |
| 非空真子集（漏选） | **满分 / 2** | **false** |
| 空选 | 0 | false |

**★ 最容易讲错的一点**：漏选给了一半分，但 `correct` 仍然是 false。
> 因为如果漏选也算 correct，正确率会虚高 —— 一个学生每道多选题都只选一个，正确率会是 100%。分数和正确率是两套语义。

**另一个细节**：用 `TreeSet` 归一化选项，保证判分结果与提交顺序无关。
> 用户提交的是 [A, C]，正确答案是 [C, A]，直接比 List 会判错。先转 TreeSet 再比。

**面试怎么讲**：
> 判分我写成了纯函数，因为这是系统里最不能错的地方。纯函数的好处是能被穷举验证 —— 我可以把「正确答案 × 所有可能的提交组合」全跑一遍，不用起容器。另一个细节是漏选给一半分但不算正确，因为算正确的话正确率就虚高了。

## 16.2 幂等：三处用同一个思路，不用分布式锁 ★★★

**先说结论**：
> 我没有用分布式锁。锁只能锁单机，多实例部署就失效了。我把幂等做在数据库上 —— 数据库是所有实例共享的唯一真相。

**三个地方，同一个思路**：

| 位置 | 做法 | 判重信号 |
|---|---|---|
| `ExamSessionService.start()` | 唯一索引 | **DuplicateKeyException** |
| `ExamSessionService.submit()` | 带条件的 UPDATE | **影响行数 == 0** |
| `GradingService.markGraded()` | 带条件的 UPDATE | **影响行数 == 0** |

**为什么这个思路比锁好**：
1. 不依赖任何单机状态，天然支持多实例
2. 数据库的原子性是有保证的（不需要你再去证明）
3. 崩溃恢复后依然正确（锁可能没释放）

**面试怎么讲**：
> 幂等我没用分布式锁，用的是数据库。开始考试靠唯一索引，重复插入会抛 DuplicateKeyException；交卷和判分靠带条件的 UPDATE，如果影响行数是 0 说明状态已经变过了。数据库是所有实例共享的，所以多实例部署也不会出问题，而且不需要考虑锁释放。

## 16.3 缓存三件事，逐个对应 ★★

`PaperCache.java` 里同时处理了穿透、击穿、雪崩：

| 问题 | 解法 | 代码里的具体值 |
|---|---|---|
| 穿透 | 空值缓存哨兵 | `__NULL__`，TTL 60s |
| 击穿 | 重建前抢锁 | base 600s |
| 雪崩 | TTL 随机抖动 | jitter 0.2（±20%） |

**两个能体现深度的细节**：

① **哨兵不能用空字符串**
> 有些客户端会把空字符串当成「不存在」，那样就白存了。要用一个不可能和真实数据冲突的值。

② **释放锁必须用 Lua**
> 因为「判断是不是自己的锁」和「删除」必须原子。如果分两步，判断完之后锁刚好过期、别人抢到了，你就会把人家的锁删掉。

## 16.4 异步判分 + 降级 ★★★

**设计**：交卷时如果开了异步判分，发消息给 RabbitMQ；发不出去就**同步判**。

```java
if (asyncGrading && gradingProducer.trySend(sessionId)) {
    // 异步：直接返回，前端轮询/WebSocket 等结果
} else {
    // 降级：同步判分
    gradingService.grade(sessionId);
}
```

**关键在 `trySend` 返回 boolean** —— 它不抛异常。MQ 挂了用户还能交卷，只是慢一点。

**消费者侧的坑（这个最有价值）**：

**错误做法**：消费者捕获异常后抛回 Broker → **立刻重投** → 无限循环 → CPU 打满。

**正确做法**：
1. 捕获异常，记日志 + 记指标（不抛回 Broker）
2. 另起**定时任务扫描**「已交卷但没有分数」的记录
3. 暴露 `exam_grading_unscored` Gauge 作为告警线

**代码注释原话**：
> 失败不会自愈，但它不再是静默的。

**面试怎么讲（这段是加分项）**：
> 异步判分我做了降级：MQ 发不出去就同步判，用户至少能交卷。消费者这边我没让它把异常抛回 Broker —— 那会无限重投把 CPU 打满。我改成捕获后记指标，另外起一个定时任务扫「已交卷但没分数」的记录，同时暴露一个 Gauge 做告警。失败不会自愈，但它不再是静默的。

## 16.5 倒计时以服务端为准 ★★

**核心**：剩余时间从库里的 `deadline_at` 算，**不接受客户端传的任何时间**。

> 学生改本地系统时间没用，因为服务端只信自己库里的 deadline。

**WebSocket 这块的诚实回答**：
> WebSocket 的水平扩展我做不到。连接存在单机内存里，多实例部署时用户连在 A 机器、推送在 B 机器，推不过去。要解决得加广播（Redis Pub/Sub）。另外倒计时里有 synchronized (session)，本质是单机锁，集群里没意义。

**为什么不慌**：这是一个**明确的已知限制**，能说清楚边界比假装没问题强得多。

---

# 附录 A：怎么用这份文档

## 三轮学习法

**第一轮（3 天）—— 建立地图**
只看每章开头的表格和「面试答法」引用块，跳过代码。目标是知道「有哪些东西」。

**第二轮（7 天）—— 填细节**
按重要度顺序：第 6、7、3、8 章先看（★★★★），再第 16、10、9 章，最后其余的。

**第三轮（2 天）—— 自测**
遮住每节的正文，只看标题，试着口述。卡住的地方做标记，回去看。

## 答不出来的自救话术

**这比硬编强得多**：

| 情况 | 话术 |
|---|---|
| 完全没听过 | 「这块我没接触过，回去我补一下。不过我可以猜一下它的思路是……」 |
| 听过没做过 | 「我知道它的原理是 X，但没在生产上用过，所以细节不敢说得太肯定。」 |
| 做过但忘了 | 「我项目里用了，具体参数我记不清了，大方向是……」 |

**绝对不要**：编造细节。面试官一追问就露馅，而且性质比「不会」严重得多。

## 面试前一天只看这些

1. 第 16 章五个设计（这是你区别于其他人的地方）
2. 第 6.4 唯一索引做幂等
3. 第 7.2 缓存三大问题
4. 第 8.3 / 8.4 不重复消费 + 消费者不抛异常
5. 第 13.3 depends_on 与 MySQL 临时实例

这五个讲清楚，项目部分就够了。

---

## 附录 B：这份文档没覆盖的

诚实说明范围：

- **没有**深入 JVM 调优（GC 日志分析、内存泄漏排查实战）
- **没有**分布式事务（Seata、TCC、Saga）
- **没有**分库分表
- **没有**Kubernetes（只讲了 Docker Compose）
- **没有**消息队列的高级特性（延迟插件、事务消息）

如果面试官问到这些，用附录 A 的自救话术。**这些不在你的项目里，不需要假装会。**

