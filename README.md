# 在线考试与自动判分系统

[![CI](https://github.com/jiejie141/online-exam/actions/workflows/ci.yml/badge.svg)](https://github.com/jiejie141/online-exam/actions/workflows/ci.yml)

一个能真的从头考到尾的考试系统：老师出题组卷发布，学生在时间窗内进入考试、答题、交卷，
系统自动判分并给出成绩分布和逐题正确率。

CI 上三个 job 都跑真东西：单元测试在 Java 17 / 21 双版本上跑，
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
| 前端 | Vue 3 + Vite + Element Plus + Pinia + Vue Router | 前后端分离，但**构建产物由后端一起托管**，所以部署仍然只有一个进程、没有 CORS |
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
| GET | `/api/exams/{examId}/paper` | 取卷面（不含正确答案） |
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

同一处还有个容易被忽略的边界：`createQuestion` 现在拒绝 `createdBy == null`。
题目的**可读性完全靠 `created_by` 过滤**，存成 NULL 的题目从此谁都看不见 ——
等于写进了一个黑洞，而且写入时一切正常。

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

## 七、测试

```
mvn test
```

144 条，全部为单元测试 / Mockito 打桩测试 / 反射静态检查，不依赖任何外部中间件：

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
| `ExamServiceTest` | 8 | 别人不能往他人卷子里加题/发布/结束（且一行都没写进去）、总分按卷面累加、及格线高于总分拒绝发布、空卷拒绝发布、题目必须有出题老师 |
| `StatsServiceTest` | 6 | 四个统计接口都过归属校验、没人判分时补 0 而不是空指针（avg/max/min 保持 null）、交卷进度把 graded 也算作已交 |

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
10. **前端没有自动化测试。** 没有 Vitest 单测、没有 Playwright 端到端，
    页面是手动点过的。后端接口有 144 条单测 + e2e 脚本兜着，
    但"页面上那个按钮点了会不会报错"目前只靠人看。
11. **前端没做按需引入。** Element Plus 是整包引入的，主 chunk 1.1 MB（gzip 370 KB），
    构建时有一条 `chunks are larger than 500 kB` 警告。做按需引入能砍掉大半，
    但那是 `unplugin-vue-components` 加一堆配置的事，对现在的使用场景（内网、桌面浏览器）
    不值得。**这条是取舍，不是遗漏。**
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
├── config/ClockConfig.java            把"现在几点"做成 Bean，方便测试固定时间
├── domain/
│   ├── entity/                        6 张表的实体
│   ├── dto/                           PaperItem(不含答案) / 统计类 DTO
│   └── mapper/                        6 个 Mapper，含统计 SQL
├── exam/
│   ├── QuestionType.java / ExamStatus.java / RecordStatus.java
│   ├── ExamService.java               老师端：出题组卷发布
│   └── ExamSessionService.java        学生端主流程
├── grading/
│   ├── GradingRule.java               判分规则（纯函数）
│   ├── GradingService.java            取数循环写回
│   ├── GradingMq.java / Producer / Consumer
├── job/AutoSubmitJob.java / ExamCloseJob.java
├── security/                          JWT + Spring Security + 登录
├── stats/StatsService.java
├── web/                               Controller + 统一响应 + 全局异常处理
└── ws/                                WebSocket 倒计时

src/main/resources/db/
├── schema.sql                         建表（每个索引都注明服务哪条查询）
└── seed.sql                           演示数据（重复执行会先清空业务表，保证结果一致）

scripts/
├── e2e.sh                             一条命令跑完端到端
├── e2e_check.py                       接口校验 + 判分延迟实测
├── bench.sh                           取卷吞吐 A/B（Redis 开 / 停）
└── bench_paper.py                     压测客户端（每线程长连接）

web/                                   前端源码（Vue 3 + Vite）
├── vite.config.js                     outDir 指向 ../src/main/resources/static
└── src/
    ├── api/http.js                    axios 实例 + 拦截器（拆壳、401 跳登录）
    ├── router/index.js                hash 路由 + 登录/角色守卫
    ├── stores/auth.js                 token 存 sessionStorage
    └── views/                         学生端 4 页 / 老师端 3 页
```
