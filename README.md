# 在线考试与自动判分系统

[![CI](https://github.com/jiejie141/online-exam/actions/workflows/ci.yml/badge.svg)](https://github.com/jiejie141/online-exam/actions/workflows/ci.yml)

一个能真的从头考到尾的考试系统：老师出题组卷发布，学生在时间窗内进入考试、答题、交卷，
系统自动判分并给出成绩分布和逐题正确率。

CI 上四个 job 都跑真东西：单元测试在 Java 17 / 21 双版本上跑，
前端 job 跑 Vitest（倒计时、多选答案拼接、401 跳转这些都在 JS 里）并真构建一次，
e2e job 连真 MySQL / Redis / RabbitMQ 跑完整流程（含判分延迟实测），
docker job 真的构建镜像，验证「缺密钥就拒绝启动」，并且把 jar 从镜像里抠出来
确认前端页面确实被打进去了（前端产物不进 git，是 Dockerfile 里现构建的，
这一环断了的话镜像照样构建成功、健康检查照样绿，只是打开是 404）。

---

## 一、它解决什么

考试系统里最容易做错、也最不该做错的三件事：

1. **判分算错** —— 多选漏选给不给分、答案顺序算不算对、没答的题算 0 还是算没判。
2. **时间不严谨** —— 学生改本地时间能不能多考半小时、刷新页面会不会重开一份卷子。
3. **人多时崩** —— 开考瞬间几百人同时拉同一份卷子，全打到数据库上。

这三件事在代码里都有对应的、可被单测钉住的处理，写在下面"设计要点"里。

## 二、技术栈

| 层 | 选型 | 为什么 |
|---|---|---|
| Web | Spring Boot 3.3.4 + **Spring MVC** | 交卷+判分要整体成功或整体失败，必须用 `@Transactional` 和 ThreadLocal，WebFlux 的事件循环模型下用不了 |
| 前端 | Vue 3 + Vite + Element Plus（按需引入）+ Pinia + Vue Router + Vitest | 前后端分离，但**构建产物由后端一起托管**，所以部署仍然只有一个进程、没有 CORS |
| ORM | MyBatis-Plus 3.5.7 | 统计查询要写 JOIN + CASE + 分组，注解式 SQL 比 XML 更容易和 Java 代码一起 review |
| 数据库 | MySQL 8 | 用到了 JSON 列、`INSERT ... AS new ON DUPLICATE KEY UPDATE` |
| 缓存 | Caffeine（L1）+ Redis（L2，Lettuce） | 两级卷面缓存，含穿透/击穿/雪崩三件事的处理 |
| 消息 | RabbitMQ | 交卷后异步判分，Broker 挂了自动降级同步判分 |
| 实时 | WebSocket | 服务端推送考试倒计时 |
| 鉴权 | Spring Security + JWT | 无状态，学生/老师两个角色 |
| 监控 | Actuator + Prometheus | — |

Java 17。

## 三、跑起来

### 1. 起依赖

```bash
docker compose up -d
```

> 拉不动镜像就把 `image` 前面加 `docker.m.daocloud.io/` 前缀（例如
> `docker.m.daocloud.io/library/mysql:8.0`），`docker-compose.yml` 注释里也有写。

### 2. 建表 + 灌演示数据

```bash
docker exec -i exam-mysql mysql -uroot -proot --default-character-set=utf8mb4 < src/main/resources/db/schema.sql
docker exec -i exam-mysql mysql -uroot -proot --default-character-set=utf8mb4 < src/main/resources/db/seed.sql
```

> **灌数据命令必须带 `--default-character-set=utf8mb4`**：mysql 客户端默认用 latin1 连接，UTF-8 中文会被按 latin1 逐字节转存，库里变成双重编码乱码——判分不受影响，但题干全是 `ä¸­` 这种字符。之前灌过的话，把上面两条命令重跑一遍即可。

演示账号（口令在 `seed.sql` 里以 BCrypt 哈希存）：

| 账号 | 口令 | 角色 |
|---|---|---|
| `t001` | `teacher123` | 老师 |
| `s001` / `s002` / `s003` | `student123` | 学生 |

种子数据里已经有一场**已发布**的考试《Java 后端基础月考（第 1 期）》，
时间窗是"昨天到 7 天后"，任何时候跑完脚本都能直接进去考。

### 3. 起服务

**先设 JWT 签名密钥**（应用不再内置默认密钥，不设会直接拒绝启动）：

```bash
export EXAM_JWT_SECRET=$(openssl rand -hex 32)   # Windows: set EXAM_JWT_SECRET=<32 字节以上的随机串>
mvn spring-boot:run
```

> 为什么是"拒绝启动"而不是"记一条警告继续跑"：一个用默认密钥跑起来的考试系统，
> 从外面看和正常的**一模一样** —— 登录能登、判分能判、健康检查是 UP。
> 它唯一的区别是任何人都能自己签一个 `role=teacher` 的令牌改成绩。
> 这种失败不会自己暴露，所以只能靠"起不来"来暴露它。

端口 `8081`。没有 Redis 也能跑（缓存会降级成直接查库），没有 RabbitMQ 也能跑（判分会降级成同步）。

想一条命令跑完"起依赖 + 建表 + 灌数据 + 起服务 + 校验 + 关服务"，用：

```bash
bash scripts/e2e.sh          # 端到端校验，含判分延迟实测
bash scripts/bench.sh        # 取卷吞吐 A/B 压测
```

### 4. 前端页面

前端在 `web/`，构建产物落到 `src/main/resources/static/`，由 Spring Boot 直接托管。
**构建产物不进 git**（见 `.gitignore`），所以 clone 下来要自己 build 一次：

```bash
cd web
npm ci            # 严格按 package-lock.json 装，版本与 CI 一致
npm test          # Vitest：40 条前端单测
npm run build     # 产物写到 ../src/main/resources/static/
```

然后起后端，浏览器打开 <http://localhost:8081/>：

| 页面 | 账号 |
|---|---|
| 学生端：我的考试 → 答题 → 成绩 | `s001` / `student123` |
| 老师端：考试管理 / 题库管理 / 成绩统计 | `t001` / `teacher123` |

改前端时用 dev 模式（热更新，5173 端口，代理到 8081 的后端）：

```bash
cd web && npm run dev
```

**为什么路由用 hash 模式**：页面由后端托管，如果走 history 模式，
用户在 `/teacher/exams` 上按 F5，请求会打到后端，而 Spring 找不到这个路径 —— 要么 404，
要么得配一条把所有路径都转发到 index.html 的规则，那条规则又会和 `/api/**` 抢路由。
hash 模式（`/#/teacher/exams`）下 `#` 之后的内容不会发给服务端，服务端永远只看到 `/`，
所以 `SecurityConfig` 里能精确地只放行 `/`、`/index.html`、`/favicon.ico`、`/assets/**` ——
**而不是 `/**`**（那会把 `/api/**` 一起放行，等于关掉整个鉴权）。

### 5. 走一遍流程

```bash
# 学生登录
TOKEN=$(curl -s -X POST http://localhost:8081/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"s001","password":"student123"}' | python -c "import sys,json;print(json.load(sys.stdin)['data']['token'])")

# 看能考什么
curl -s http://localhost:8081/api/exams/available -H "Authorization: Bearer $TOKEN"

# 进入考试（返回 recordId、deadlineAt、卷面）
curl -s -X POST http://localhost:8081/api/exams/1/start -H "Authorization: Bearer $TOKEN"

# 存答案
curl -s -X POST http://localhost:8081/api/records/1/answers -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"questionId":1,"answer":"B"}'

# 交卷（判分是异步的，稍等再查）
curl -s -X POST http://localhost:8081/api/records/1/submit -H "Authorization: Bearer $TOKEN"
curl -s http://localhost:8081/api/records/1/result -H "Authorization: Bearer $TOKEN"

# 老师看统计
TT=$(curl -s -X POST http://localhost:8081/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"t001","password":"teacher123"}' | python -c "import sys,json;print(json.load(sys.stdin)['data']['token'])")
curl -s http://localhost:8081/api/teacher/exams/1/stats       -H "Authorization: Bearer $TT"
curl -s http://localhost:8081/api/teacher/exams/1/distribution -H "Authorization: Bearer $TT"
curl -s http://localhost:8081/api/teacher/exams/1/accuracy     -H "Authorization: Bearer $TT"
```

### 6. 倒计时（WebSocket）

```javascript
const ws = new WebSocket(`ws://localhost:8081/ws/countdown?token=${TOKEN}&examId=1`);
ws.onmessage = e => console.log(e.data);   // {"remainingSeconds":3540,"ended":false}
```

## 四、接口

学生端：

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/auth/login` | 登录，返回 JWT |
| GET | `/api/exams/available` | 当前可参加的考试 |
| POST | `/api/exams/{examId}/start` | 进入考试（重复进入返回原答卷） |
| GET | `/api/exams/{examId}/paper` | 取卷面（不含正确答案；**必须进过这场考试**） |
| POST | `/api/records/{recordId}/answers` | 保存/覆盖一道题的作答 |
| POST | `/api/records/{recordId}/submit` | 交卷（重复点安全） |
| GET | `/api/records/{recordId}/result` | 查成绩 |

老师端（需 `ROLE_teacher`，且只能操作**自己建的**考试和题目，见设计要点第 8 条）：

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/teacher/users` | 建账号 |
| POST | `/api/teacher/questions` | 出题 |
| GET | `/api/teacher/questions` | 题库（只含自己出的题） |
| POST | `/api/teacher/exams` | 建考试（草稿） |
| GET | `/api/teacher/exams` | 自己的考试列表（含草稿） |
| POST | `/api/teacher/exams/{id}/questions` | 组卷 |
| POST | `/api/teacher/exams/{id}/publish` | 发布（总分按卷面自动累加） |
| POST | `/api/teacher/exams/{id}/close` | 结束 |
| GET | `/api/teacher/exams/{id}/stats` | 平均分/最高/最低/及格人数 |
| GET | `/api/teacher/exams/{id}/distribution` | 成绩分布（10 分一档） |
| GET | `/api/teacher/exams/{id}/accuracy` | 逐题正确率，最低排最前 |
| GET | `/api/teacher/exams/{id}/progress` | 已交卷 / 未交卷人数 |

## 五、设计要点

### 1. 判分规则是纯函数（`grading/GradingRule.java`）

| 题型 | 完全一致 | 漏选 | 错选 | 未作答 |
|---|---|---|---|---|
| 单选 / 判断 | 满分 | — | 0 | 0 |
| 多选 | 满分 | **半分，但 `correct=false`** | 0 | 0 |

- 多选答案**顺序无关**：`TreeSet` 归一化后比较，`"BA"` 和 `"A,B"` 是同一个答案。
- 漏选给半分但**不算对**——标成"对"会让正确率统计虚高。
- 题目没配答案时抛 `IllegalStateException`，不能默认把学生判成错。

### 2. 截止时间在进入时固定，且被考试窗口截断（`exam/ExamSessionService.java`）

```
deadline = min(开始时间 + 时长, 考试 end_at)
```

9:50 进场、时长 60 分钟、考试 10:00 结束 → 只能考到 10:00，不会考到 10:50。
存进 `deadline_at` 而不是每次现算，所以老师中途改考试时长不会影响正在考的人。

### 3. 幂等交给数据库，不靠"先查一下"

| 场景 | 机制 |
|---|---|
| 一人一场只有一份答卷 | `uk_record_exam_user(exam_id, user_id)`，冲突就 `DuplicateKeyException` 后读回 |
| 一道题一份答卷只有一条作答 | `uk_answer_record_question` + `ON DUPLICATE KEY UPDATE` |
| 重复交卷只判一次 | `UPDATE ... WHERE status='ongoing'`，影响行数 0 说明别人先做了 |
| 重复判分不加两遍 | `UPDATE ... WHERE status='submitted'`，0 行就抛错让事务回滚 |

### 4. 两级缓存 + 穿透/击穿/雪崩（`cache/PaperCache.java`）

- **L1 本地（Caffeine）+ L2 Redis**。开考瞬间几百人拉同一份卷子，走 L1 时连网络都不出。
- **穿透**：空结果也缓存，存哨兵 `__NULL__` 而不是空串。**L1 也要写**——否则空卷面每 30 秒还是要打一次 Redis，穿透防护只在 L2 生效。
- **击穿**：重建前 `setIfAbsent` 抢锁，抢到的才查库，释放用 Lua 保证只删自己的锁。
- **雪崩**：TTL 加 ±20% 抖动，`jitteredTtl()` 是纯函数（随机数由调用方传入）所以能单测。
- **额外**：Redis 挂了全部降级成直接查库——缓存是加速用的，不能因为它抖一下就考不了试。

**两级缓存最容易被问的地方是一致性，这里如实说**：卷面一旦发布就不再变（`PaperItem` 是只读快照），
所以 L1 允许陈旧。代价写死在配置里：L1 的 TTL 是 **30 秒**，远短于 L2 的 600 秒 ——
另一个实例发布新卷后，本实例最多 30 秒还读到旧卷。**如果哪天卷面要支持编辑，
这个设计必须先改成广播失效，而不是把 TTL 调小**：调小只是把窗口变窄，不是消掉。
另外 `maximumSize` 必须设，否则 L1 就是一个只进不出的 Map，等于内存泄漏。

### 5. 异步判分 + 降级（`grading/`）

交卷只做"改状态 + 记时间"，判分丢 RabbitMQ。Broker 不可用时 `trySend` 返回 false，
当场同步判掉——慢一点，但不会把卷子永远挂在"已交卷未判分"。

### 6. 超时自动收卷（`job/AutoSubmitJob.java`）

学生关掉浏览器就走了不会点交卷，所以每 30 秒扫一次 `status='ongoing' AND deadline_at <= now()`，
分批（每批 200）收卷并触发判分。用时按"开始 → 应截止"记，服务停了十分钟不算学生头上。

### 7. 倒计时由服务端算（`ws/CountdownHandler.java`）

剩余时间用**数据库里的 `deadline_at`** 和**服务端时钟**算，客户端传什么都不影响。
改浏览器本地时间换不到额外时间。

### 8. 老师端按 `created_by` 判归属（`exam/ExamAccess.java`）

只校验 `ROLE_teacher` 回答的是「你是不是老师」，**不是**「这场考试是不是你的」。
少了后者，任何老师知道 `examId` 就能发布、结束别人的考试，还能看别人那场考试的全班成绩。

所以七个入口（`publish` / `close` / `addQuestions` / `stats` / `distribution` /
`accuracy` / `progress`）统一走 `ExamAccess.requireOwned(examId, teacherId)`，
列表类接口（`listExams` / `listQuestions`）在 wrapper 上加 `created_by = 当前用户`。

三个刻意的选择：

- **抽成一个类，不在各方法里各写一遍。** 同一个判断散在七处，等于给后来加端点的人
  留一个「忘了写」的机会 —— 而忘了写**不会报错，只会静默放行**。集中之后，
  新端点不走它就**拿不到 `Exam` 对象**（方法返回它），漏了很显眼。
- **用 403 不用 404。** 考试确实存在，只是不属于你。用 404 会让「不存在」和
  「不是你的」变成同一种回答，反而方便别人试探哪些 id 是真的。
  学生端的 `requireOwnRecord` 用的是同一口径。
- **`created_by` 为 NULL 时拒绝，不是放行（fail-closed）。** 这个库里所有考试都经过
  `createExam` 创建，它一定写 `created_by`，所以这条分支只会被手写 SQL 造出的
  无主数据命中。真出现了，正确做法是回填 `created_by`，而不是让所有老师都能改一场
  谁都不负责的考试。

学生端也有同一类问题，而且是更严重的那个：`GET /api/exams/{examId}/paper`
原来只看 `examId`。任何登录用户猜一个 id 就能把整场卷子拿走 —— 包括**还没开考**的那场
（计划明天上午考，今天就能把题看完）。这不是"看到别人的数据"，是直接毁掉一场考试。
现在 `paperForStudent(examId, userId)` 先查"这个人有没有自己的答卷"再给卷：
答卷只可能在 `start()` 通过"已发布 + 开考 + 未结束"三道校验之后才被创建出来，
所以"我有一份答卷"本身就已经证明了当时是合法进场，不必再抄一遍时间窗判断。
并且**不区分"考试不存在"和"你没进过"** —— 两种都回 403 同一条消息，
分开回答等于送一个探测 `examId` 是否有效的接口。

卷面的内部读取路径（`loadPaper`）不做权限判断，只给 `start` / `result` / `saveAnswer`
这些**已经各自校验过身份**的地方用；对外只暴露 `paperForStudent`。

同一处还有个容易被忽略的边界：`createQuestion` 现在拒绝 `createdBy == null`。
题目的**可读性完全靠 `created_by` 过滤**，存成 NULL 的题目从此谁都看不见 ——
等于写进了一个黑洞，而且写入时一切正常。

反过来也有一个洞：`addQuestions` 原来只判"这场考试是你的"，没判"这些题是你的"。
题库里别人的题可以被悄悄塞进自己的卷子，学生考完看到的是一道你没出过、
也看不到答案的题。现在每道题都过一遍 `createdBy`，同样 fail-closed。

而且这个方法**没有 `@Transactional`**，所以顺序本身是逻辑的一部分：先把整份列表校验完，
再统一写入。边校验边写的话，列表中间夹着一道别人的题时，前面几道已经落库，
卷子成了改了一半的半成品，而调用方只看到一个 403 —— 这个坑有专门的用例钉着
（`列表里夹着别人的题目就一道都不写`）。

### 9. 四个工程设施：分页 / 多环境 / traceId / 优雅停机与任务锁

这四条都不是业务功能，但少了它们，这个项目只在我自己机器上成立。

**（1）列表分页。** 老师端的考试列表和题库列表原来是一次全量 `selectList`。
题库几百道题时不明显，上千道时每次打开页面都要把整表拉回来、再全量序列化一遍。
现在返回 `{items,total,page,size}`，由 MyBatis-Plus 的 `PaginationInnerInterceptor` 拼 `LIMIT`。
两个细节：`page` 从 **1** 开始，传 0 或负数夹到 1 而不是 400（翻页不该成为错误来源）；
`size` 上限 100，并且**在 controller 里显式夹一遍**，不依赖插件静默改写 ——
否则返回的 `size` 字段会和请求对不上，前端算页数就错。
配套加了 `V3__exam_teacher_list_index.sql`：`(created_by, start_at)`。
等值列放最左、排序列放右，避免 filesort；**没有加 DESC 索引** —— B+ 树反向扫描代价一样，
单独再建一个索引只会多一份写入开销。

学生端的 `/api/exams/available` **刻意不分页**：它被时间窗（已发布 + 已开考 + 未结束）
天然限制在很小的结果集里，加分页只会让前端多一层无意义的翻页。

**（2）多环境配置。** 原来只有一份 `application.yml`，数据库口令是 `root/root` 写在文件里，
生产要改只能手改再提交 —— 而「改完忘了改回来」是这类配置最常见的坏结局。
现在拆成 `application.yml`（公共）+ `application-dev.yml` + `application-prod.yml`，
`spring.profiles.active` 默认 `dev`，所有凭据在 prod 里都写成 `${EXAM_DB_PASSWORD}` 形式**不给默认值**。

光拆文件还不够 —— **不给默认值只会在连库那一刻才炸**，而那时应用已经起了一半。
所以加了 `config/ProdConfigGuard.java`：`@Profile("prod")` + `@PostConstruct`，
启动时一次性检查三个口令（非空、不在示例口令集、≥8 字符）和 JWT 密钥（≥32 字节、字符种类 ≥8），
**把问题一次全列出来再拒绝启动**。10 条单测钉住，包括「32 个 a 也能过长度检查，但那不叫密钥」。

**（3）traceId 贯穿日志。** 没有它的时候，一个请求打出的几行日志和其他请求的日志混在一起，
排查只能靠时间戳对齐 —— 并发一高就彻底不可读。
`web/TraceIdFilter.java` 是 `Ordered.HIGHEST_PRECEDENCE` 的 `OncePerRequestFilter`：
读 `X-Request-Id`，没有就生成 16 位，放进 MDC，同时写回响应头；`finally` 里 `MDC.remove`
（**线程池复用线程，不清就会串号** —— 这条有专门的用例）。
传进来的 id 只接受字母数字和 `-_.` 且不超过 64 字符，否则重新生成：
日志里的值是外部可控的，不校验就等于允许往日志里注入换行。
`logback-spring.xml` 的 pattern 里带 `%X{traceId:-}`，**刻意不用 `%clr()`** ——
ANSI 颜色码会让 `grep` 把日志文件判成二进制、只回一句 `Binary file matches` 而不给行号。

**（4）优雅停机 + 定时任务锁。** `server.shutdown: graceful` + `timeout-per-shutdown-phase: 30s`：
停机时先不再接新请求，等在途的请求做完再退。没有它，一次滚动发布就会把正在交卷的人打断。

定时任务（`AutoSubmitJob` / `ExamCloseJob` / `GradingSweepJob`）在多实例下会被每个实例各跑一遍。
`job/JobLock.java` 用 Redis `setIfAbsent` 抢锁、Lua 脚本校验 token 后释放（和 `PaperCache` 同一套写法）。
**关键取舍：Redis 不可用时降级成无锁执行，而不是跳过这一轮。**
锁在这里是「避免重复劳动」的优化，不是正确性前提 —— 三个任务本身都是幂等的
（自动收卷只改状态未变的记录、关考只关已到点的、扫描只是读）。
反过来如果 Redis 一挂就停止收卷，那才是真的把可用性绑在了缓存上。
这条分支有变异验证：把 `return task.get()` 改成 `return null`，`JobLockTest` 立刻红。

## 六、实测数字

全部来自 `scripts/e2e.sh` 和 `scripts/bench.sh` 的真实运行输出，不是估算。

### 判分（`bash scripts/e2e.sh`，50 名学生各交一份 10 题卷）

| 指标 | 实测 |
|---|---|
| 判分正确性 | 50 份全部 87 分，与手算值完全一致（含多选漏选给半分） |
| 交卷接口耗时 | avg 10 ms，p99 33 ms |
| 交卷 → 出分（异步 MQ） | avg 28 ms，p50 26 ms，p99 48 ms |

> 一开始轮询间隔设成 50 ms，测出来的延迟被量化成 50/100/150 三档，p99 完全失真。
> 降到 10 ms 才拿到上面这组数。**测量精度会被测量方法本身限制**，这点写在脚本注释里了。

### 取卷吞吐（`bash scripts/bench.sh`，100 并发 × 10 秒，A/B 两组）

| | 缓存开启（走 Redis） | 缓存降级（Redis 停掉，全走 MySQL） |
|---|---|---|
| QPS | **4773 / 4687**（两轮） | **723 / 724**（两轮） |
| p50 | 18 / 19 ms | 135 / 135 ms |
| p99 | 55 / 56 ms | 270 / 273 ms |

即：缓存让取卷吞吐提升约 **6.5 倍**，p99 延迟降到约 **1/5**。

**关于可复现性，这里有个必须说的坑**：最早测出来的缓存组 QPS 在 1350 / 2047 / 4678 之间乱跳，
差了三倍多。查下来不是代码问题，而是同机上另一个容器在崩溃重启循环里吃 CPU。
把它停掉之后连跑两轮，数字稳定在 ±2% 以内。
**所以压测前先看一眼 `docker ps`，有容器在 Restarting 就别测了——测出来的是噪声。**

另有两点如实说明：
- 压测客户端和服务跑在同一台机器上，QPS 是保守值（客户端本身也吃 CPU）。
- 缓存降级组稳定在 ~723 QPS，瓶颈是 Hikari 连接池 `maximum-pool-size: 20`。

### 缓存到底省掉了哪一跳（开 `general_log` 数出来的）

不猜，直接开 MySQL 的 general_log 数 SQL。同样是"预热 300 次 + 正式发压"：

| | 请求数（含预热 300） | `FROM exam_record` | 卷面 `exam_question JOIN question` |
|---|---|---|---|
| 缓存开启 | 594 | 595 条，**每请求 1 条** | **1 条**（只有重建缓存那一次） |
| 缓存降级 | 360 | 361 条，**每请求 1 条** | 361 条，**每请求 1 条** |

也就是缓存省掉的正是那条带 `options` 大字段的 JOIN，而
`findByExamAndUser` 那条**每请求必打、也省不掉** —— 它是"你有没有进过场"的判据，
是权限闸门，不能拿缓存绕过（否则卷面越权那个洞就从缓存里重新打开了）。
**"缓存命中率高"不等于"零 SQL"**，这个区别值得在面试里讲清楚。

### 换个环境复测：比值稳定，绝对值随机器负载走

同一套 `bench.sh` 在另一台机器上重跑（100 并发 × 10 秒，A 组连跑四次）：
**QPS 1732 / 1965 / 2003 / 2009**，p50 6–15 ms，p99 395–568 ms；
停掉 Redis 再测：**QPS 250**，avg 392 ms，p99 811 ms。**A/B 仍是 8 倍左右，比值对得上。**

绝对值比上面低了一半多，原因不在代码：这台机器上同时跑着编辑器和 agent（两个 node
进程常驻，`loadavg` 1.1–1.7，4 GB 内存用掉 3 GB）。**吃 CPU 的东西不一定在容器里**——
这正是上面那个"压测前先看 `docker ps`"的坑的另一个版本。

所以引用这些数字时要说清口径：**"在这台开发机上实测 X"**，而不是"这个系统能扛 X"。

## 七、测试

```
mvn test
```

178 条，全部为单元测试 / Mockito 打桩测试 / 反射静态检查，不依赖任何外部中间件：

| 测试类 | 条数 | 覆盖 |
|---|---|---|
| `GradingRuleTest` | 13 | 单选/多选/判断的满分、漏选半分、错选、顺序无关、空答案、题目没配答案 |
| `GradingServiceTest` | 6 | 总分累加、重复判分返回原分、未交卷不判、并发下回滚、题目被移出卷子 |
| `GradingConsumerTest` | 5 | 正常重复静默、真失败计指标、异常不逃回 Broker |
| `ExamSessionServiceTest` | 24 | 截止时间三种边界、未发布/未开考、重复进入（含已交卷后的状态）、并发进场撞键、超时不能存答案、卷外题不能存、重复交卷、投递失败降级、用时口径、越权访问 |
| `PaperCacheTest` | 19 | TTL 抖动边界、空值哨兵、坏数据自删、抢锁、Redis 挂掉时的降级、L1 命中不碰 Redis、空卷面也写 L1、Redis 命中回填 L1、evict 两层都清、多实例下 L1 的最长不一致窗口 |
| `JwtUtilTest` | 6 | 签发/解析、角色与 userId 往返、过期、篡改、错密钥 |
| `AuthServiceTest` | 6 | 登录不泄露用户名是否存在、角色白名单、建账号并发撞唯一键 |
| `GradingSweepJobTest` | 3 | 漏判数进指标、**显式刷 0**、扫描失败不搞死调度线程 |
| `ExamCloseJobTest` | 3 | 到点关考、**一场失败不跳过剩下几场** |
| `CountdownHandlerTest` | 12 | 未进场不让连、到点推 0 而不是负数、推送失败摘连接、广播与在线数 |
| `StateMachineTest` | 5 | 题型/考试/答卷三种枚举的迁移规则 |
| `GlobalExceptionHandlerTest` | 14 | 异常到 HTTP 的翻译：业务异常状态码跟着 code 走（401/403/404/降级 400）、四类客户端错误不被吞成 500、500 不泄露堆栈 |
| `PaperItemTest` | 5 | 卷面 DTO 无 `answer` 字段（字段反射 + 序列化双重检查）、该有的字段一个不少、判断题不发 options |
| `MapperAliasTest` | 5 | 统计 SQL 的每个 `AS` 别名都能落到对应 DTO 的字段上（见下面第 1 条：这是唯一一个不连库却能抓到 SQL 列名错误的测试） |
| `ExamAccessTest` | 4 | 老师端归属闸门：自己的考试返回原对象、别人的 403 而非 404、不存在 400、`created_by` 为空时拒绝而不是放行 |
| `ExamServiceTest` | 11 | 别人不能往他人卷子里加题/发布/结束（且一行都没写进去）、不能把别人的题目加进自己卷子（列表里夹一道就一道都不写）、总分按卷面累加、及格线高于总分拒绝发布、空卷拒绝发布、题目必须有出题老师 |
| `PaperAccessTest` | 6 | 没进过这场考试取不到卷面（且此时既不查库也不查缓存）、进过之后可以取、别人的答卷不因 examId 相同对我开放、考试不存在与没进过给同一个回答、进场自己取卷不受影响、交卷后仍能取原题渲染成绩单 |
| `StatsServiceTest` | 6 | 四个统计接口都过归属校验、没人判分时补 0 而不是空指针（avg/max/min 保持 null）、交卷进度把 graded 也算作已交 |
| `JobLockTest` | 7 | 抢到锁才执行、抢不到返回 null 不执行、**Redis 挂了照样执行（降级无锁）**、执行完释放、抛异常也释放、释放要校验 token（不删别人的锁）、TTL 会过期 |
| `TraceIdFilterTest` | 8 | 无请求头时生成 16 位、有请求头时透传、超长/带非法字符的请求头被丢弃重生成、响应头一定回写、MDC 在 finally 里清掉（线程复用不串号） |
| `ProdConfigGuardTest` | 10 | 生产配置体检：示例口令（root/password/guest，大小写不敏感）、空口令、过短口令、JWT 密钥过短、JWT 密钥字符种类不足全部拒绝启动，且**多个问题一次性全列出来** |

### 前端单测

```bash
cd web && npm test      # vitest run，40 条
```

| 文件 | 条数 | 覆盖 |
|---|---|---|
| `src/utils/exam.test.js` | 18 | 倒计时三个坑（字段构造本地时间、毫秒尾巴、负数钳到 00:00）、选项 JSON 坏了返空数组而不是白屏、多选答案 `DAC == ACD`、空串拆出 `[]` 而不是 `['']` |
| `src/views/student/ExamTakingView.test.js` | 12 | 答题卡三色与点击跳转、标记不影响作答、交卷弹窗列出未答题号、加载失败停在答题页而不把人送回列表、点重试真的重发、多选先排序再提交、每答一题立刻落库 |
| `src/api/http.test.js` | 10 | 拆 `ApiResponse` 壳、code 非 0 不混进成功分支、401 先清会话再带 `redirect` 回跳、登录页上不重复 push、超时给中文文案、有 token 才带 `Bearer` |

这几条都是**会让页面出错的逻辑**，不是「渲染出来了就算过」的快照测试。写完做过变异验证：
把 `sort().join('')` 改成 `join('')`、把答题卡格子清空、把错误态改回 `ElMessage` 弹一下 ——
对应的 6 条用例立刻变红，说明它们真的在盯行为而不是在盯常量。

## 八、已知限制（没做的，不装作做了）

1. **SQL 的`执行`不在 `mvn test` 里验证，但 SQL 的`列名`验证了。** SQL 用了 MySQL 专有语法
   （JSON 列、`INSERT ... AS new ON DUPLICATE KEY UPDATE`），H2 跑出来的"通过"是假的，
   所以没引入 H2。真实 SQL 靠 `scripts/e2e.sh` 连真 MySQL 跑；CI 的 e2e job 会跑它
   （见 .github/workflows/ci.yml），所以现在是**每次 push 都会验**，
   不再是"只在我机器上验过"。

   不过有一条 SQL 错误**不需要连库就能抓**：列别名和 DTO 字段对不上。
   MyBatis 的 `map-underscore-to-camel-case` 只负责把 snake_case 转驼峰，
   如果 SQL 写 `COUNT(*) AS attempt_count` 而 DTO 字段叫 `attempt`，
   两边对不上时**它不抛异常**，只是把 `attempt` 留成 null —— 接口 HTTP 200、
   JSON 里有 data、每行的 `correctCount` 也是对的，只有页面上「作答人数」整列空白。
   这个 bug 真实发生过，而且 e2e 脚本当时只断言了 `correctCount`，一直是绿的。

   `MapperAliasTest` 就是为它写的：用反射读出每个 `@Select` 的别名文本，
   按 MyBatis 的规则转成驼峰，再核对 DTO 上确实有那个字段。
   它不执行 SQL、不连数据库，但能挡住这一类错误，而且是**全覆盖**的 ——
   以后再加统计 SQL，别名写错会直接红在 `mvn test`。
2. **判分失败不会自动重试 —— 但会被发现。** 消费者吞掉异常不抛回 Broker
   （抛回去会无限重投），所以失败不会自愈。要做对需要死信队列 + 重试计数。

   不过"发现"这一半已经落地了：`GradingSweepJob` 每 30 秒数一遍
   `status='submitted' AND score IS NULL` 的记录，写进指标
   `exam_grading_unscored`（另外 `exam_grading_failed_total` 记失败累计）。
   两者在 `/actuator/prometheus` 上 —— **这个数持续大于 0 就说明有卷子卡住了**。

   ⚠️ 该端点**不在**匿名放行名单里（只有 `/actuator/health` 和 `/actuator/info` 是），
   所以抓取时要带上令牌。真接 Prometheus 通常要给它单独开一条放行的抓取端点，
   或限制在内网 —— **这一步还没做**。目前的状态是「指标已经在了」，
   离「有人盯着看」还差一个抓取配置。

   在此之前这里写的是"靠 score IS NULL 的记录数去发现漏判"，但那个查询
   **并不存在**，只是注释里的承诺；而且消费者把"并发下的正常重复判分"和
   "真的判不了分"当成同一种异常一起吞成 warn —— 后者永远不会有人发现。
   现在两者分开：正常重复静默，真失败计指标 + error 级日志。
   **失败仍然不会自愈，但它不再是静默的。**
3. **没有防作弊**：无切屏检测、无题目乱序、无选项乱序。
4. **成绩统计没做缓存**，老师反复刷新统计页会直接打数据库。
5. **只有学生/老师两个角色**，没有"一门课多个老师""班级"这类概念。
6. **WebSocket 没做水平扩展**：连接存在单机内存里，多实例部署需要换成广播（Redis Pub/Sub 或 Broker）。
7. **JWT 无法主动失效**，改密码或踢人需要额外的令牌黑名单。
8. **没有文件上传/富文本题干**，题干是纯文本。
9. **Dockerfile 本机构建不了，是 CI 验的。** 本机 Docker Desktop 配了一个
   已经不存在的代理（127.0.0.1:7890），任何镜像都拉不下来。CI 的 docker job
   在能联网的 runner 上真跑：构建镜像 → 验证缺密钥拒绝启动 → 把 jar 抠出来
   确认 `BOOT-INF/classes/static/index.html` 在里面。
   **最后这条不是多余的**：前端产物不进 git，镜像里那份是 Dockerfile 的 node 阶段
   现构建再 COPY 的，这一环断了的话镜像照样构建成功、容器照样起、健康检查照样绿，
   只是打开是 404 —— 没有任何一条断言会碰到它。
10. **前端只有单测，没有端到端。** Vitest 40 条钉住的是纯逻辑与组件行为：
    倒计时用字段构造而不是 `new Date(s)`（差 8 小时那个坑）、多选答案先排序再拼
    （`DAC` 与 `ACD` 必须是同一个答案）、401 之后清 store 并带 `redirect` 回跳、
    答题卡 / 交卷弹窗 / 加载失败三态。**没有 Playwright**，所以「后端改坏了但
    前端单测仍然绿」这种情况抓不到 —— 接口契约靠后端 178 条单测与 e2e 脚本兜。
11. **Element Plus 是深路径按需引入，不是官方 resolver。** 一开始按文档配
    `unplugin-vue-components` 的 `ElementPlusResolver`，产物一点没瘦：它在
    element-plus 2.x 上解析到的是 `element-plus/es` 这个 barrel，而 barrel 里的
    `installer` 把所有组件都 import 了一遍，打包器无法证明无副作用，一个都摇不掉。
    改成自写 resolver、直接指向 `element-plus/es/components/<dir>/index` 之后，
    JS 从 1.11 MB 降到 657 KB（gzip 370 → 230 KB），那条 500 kB 警告才消失。
    **这是「按需引入」这四个字最容易骗人的地方：装了插件不等于摇掉了。**
12. **迁移 V1 是事后基线，不是从一开始就有的。** V1 的内容等于原来的
    `schema.sql`，所以从零建库、和按旧 schema.sql 建过的库，
    会走到不同的分支（一个真跑 V1，一个被 baseline 成 1）。两者最终结构一致，
    但 `flyway_schema_history` 里的记录不同 —— 这是"中途引入迁移工具"
    必然留下的痕迹，不是 bug。

## 九、目录

```
src/main/java/com/jiejie/exam/
├── OnlineExamApplication.java
├── cache/PaperCache.java              试卷缓存（三问题 + 降级）
├── config/
│   ├── ClockConfig.java               把"现在几点"做成 Bean，方便测试固定时间
│   ├── MybatisPlusConfig.java         分页插件（上限 100 条，不静默改写）
│   └── ProdConfigGuard.java           prod 启动体检：弱口令 / 弱密钥直接拒绝启动
├── domain/
│   ├── entity/                        6 张表的实体
│   ├── dto/                           PaperItem(不含答案) / PageResult / 统计类 DTO
│   └── mapper/                        6 个 Mapper，含统计 SQL
├── exam/
│   ├── QuestionType.java / ExamStatus.java / RecordStatus.java
│   ├── ExamService.java               老师端：出题组卷发布
│   └── ExamSessionService.java        学生端主流程
├── grading/
│   ├── GradingRule.java               判分规则（纯函数）
│   ├── GradingService.java            取数循环写回
│   ├── GradingMq.java / Producer / Consumer
├── job/
│   ├── AutoSubmitJob.java             超时自动收卷（30s 一轮）
│   ├── ExamCloseJob.java              到点关考
│   ├── GradingSweepJob.java           漏判扫描，写指标
│   └── JobLock.java                   Redis 抢锁；**抢不到跳过、Redis 挂了照跑**
├── security/                          JWT + Spring Security + 登录
├── stats/StatsService.java
├── web/
│   ├── Controller 若干                学生端 / 老师端 / 登录
│   ├── ApiResponse / BusinessException 统一响应与业务异常
│   ├── GlobalExceptionHandler.java    异常到 HTTP 的翻译（业务 code 决定状态码）
│   └── TraceIdFilter.java             X-Request-Id → MDC，贯穿整条日志
└── ws/                                WebSocket 倒计时

src/main/resources/
├── application.yml                    公共配置；profile 默认 dev
├── application-dev.yml                本地依赖 127.0.0.1，凭据带默认值
├── application-prod.yml               **凭据不给默认值**，配错就起不来
├── logback-spring.xml                 日志 pattern 带 traceId（不用 ANSI 颜色）
└── db/
    ├── schema.sql                     建表（每个索引都注明服务哪条查询）
    ├── seed.sql                       演示数据（重复执行会先清空业务表，保证结果一致）
    └── migration/                     Flyway V1 基线 / V2 补列 / V3 老师端列表索引

scripts/
├── e2e.sh                             一条命令跑完端到端
├── e2e_check.py                       接口校验 + 判分延迟实测
├── bench.sh                           取卷吞吐 A/B（Redis 开 / 停）
└── bench_paper.py                     压测客户端（每线程长连接）

web/                                   前端源码（Vue 3 + Vite，20 个源文件 + 3 个测试文件 + 1 个测试 setup，共 24 个 .js/.vue/.css）
├── vite.config.js                     outDir 指向 ../src/main/resources/static；
│                                      自写 Element Plus 深路径 resolver + vitest 配置
└── src/
    ├── api/http.js                    axios 实例 + 拦截器（拆壳、401 跳登录）
    ├── api/http.test.js               拦截器单测（拆壳 / 401 / 超时文案 / Bearer）
    ├── components/                    骨架屏 / 错误态 / 空状态三件套
    ├── router/index.js                hash 路由 + 登录/角色守卫
    ├── stores/auth.js                 token 存 sessionStorage
    ├── utils/exam.js                  倒计时 / 选项 / 多选答案的纯函数（+ exam.test.js）
    └── views/                         学生端 4 页 / 老师端 3 页（含答题卡与三态）
```
