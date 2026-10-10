# 在线考试与自动判分系统

一个能真的从头考到尾的考试系统：老师出题组卷发布，学生在时间窗内进入考试、答题、交卷，
系统自动判分并给出成绩分布和逐题正确率。

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
| ORM | MyBatis-Plus 3.5.7 | 统计查询要写 JOIN + CASE + 分组，注解式 SQL 比 XML 更容易和 Java 代码一起 review |
| 数据库 | MySQL 8 | 用到了 JSON 列、`INSERT ... AS new ON DUPLICATE KEY UPDATE` |
| 缓存 | Redis（Lettuce） | 卷面缓存，含穿透/击穿/雪崩三件事的处理 |
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

### 4. 走一遍流程

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

### 5. 倒计时（WebSocket）

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

老师端（需 `ROLE_teacher`）：

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/teacher/users` | 建账号 |
| POST | `/api/teacher/questions` | 出题 |
| POST | `/api/teacher/exams` | 建考试（草稿） |
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

### 4. 缓存三件事（`cache/PaperCache.java`）

- **穿透**：空结果也缓存，存哨兵 `__NULL__` 而不是空串。
- **击穿**：重建前 `setIfAbsent` 抢锁，抢到的才查库，释放用 Lua 保证只删自己的锁。
- **雪崩**：TTL 加 ±20% 抖动，`jitteredTtl()` 是纯函数（随机数由调用方传入）所以能单测。
- **额外**：Redis 挂了全部降级成直接查库——缓存是加速用的，不能因为它抖一下就考不了试。

### 5. 异步判分 + 降级（`grading/`）

交卷只做"改状态 + 记时间"，判分丢 RabbitMQ。Broker 不可用时 `trySend` 返回 false，
当场同步判掉——慢一点，但不会把卷子永远挂在"已交卷未判分"。

### 6. 超时自动收卷（`job/AutoSubmitJob.java`）

学生关掉浏览器就走了不会点交卷，所以每 30 秒扫一次 `status='ongoing' AND deadline_at <= now()`，
分批（每批 200）收卷并触发判分。用时按"开始 → 应截止"记，服务停了十分钟不算学生头上。

### 7. 倒计时由服务端算（`ws/CountdownHandler.java`）

剩余时间用**数据库里的 `deadline_at`** 和**服务端时钟**算，客户端传什么都不影响。
改浏览器本地时间换不到额外时间。

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

58 条，全部为单元测试 / Mockito 打桩测试，不依赖任何外部中间件：

| 测试类 | 条数 | 覆盖 |
|---|---|---|
| `GradingRuleTest` | 13 | 单选/多选/判断的满分、漏选半分、错选、顺序无关、空答案、题目没配答案 |
| `GradingServiceTest` | 6 | 总分累加、重复判分返回原分、未交卷不判、并发下回滚、题目被移出卷子 |
| `ExamSessionServiceTest` | 20 | 截止时间三种边界、未发布/未开考、重复进入、并发进场撞键、超时不能存答案、卷外题不能存、重复交卷、投递失败降级、越权访问 |
| `PaperCacheTest` | 14 | TTL 抖动边界、空值哨兵、坏数据自删、抢锁、Redis 挂掉时的降级 |
| `StateMachineTest` | 5 | 题型/考试/答卷三种枚举的迁移规则 |

## 八、已知限制（没做的，不装作做了）

1. **SQL 不在 `mvn test` 里验证。** SQL 用了 MySQL 专有语法（JSON 列、
   `INSERT ... AS new ON DUPLICATE KEY UPDATE`），H2 跑出来的"通过"是假的，所以没引入 H2。
   真实 SQL 靠 `scripts/e2e.sh` 连真 MySQL 跑；CI 的 e2e job 会跑它
   （见 .github/workflows/ci.yml），所以现在是**每次 push 都会验**，
   不再是"只在我机器上验过"。
2. **判分失败不会自动重试。** 消费者吞掉异常只记日志，靠"score 为 NULL 的已交卷记录数"
   去发现漏判。要做对需要死信队列 + 重试计数，见 `GradingConsumer` 的注释。
3. **没有防作弊**：无切屏检测、无题目乱序、无选项乱序。
4. **成绩统计没做缓存**，老师反复刷新统计页会直接打数据库。
5. **只有学生/老师两个角色**，没有"一门课多个老师""班级"这类概念。
6. **WebSocket 没做水平扩展**：连接存在单机内存里，多实例部署需要换成广播（Redis Pub/Sub 或 Broker）。
7. **JWT 无法主动失效**，改密码或踢人需要额外的令牌黑名单。
8. **没有文件上传/富文本题干**，题干是纯文本。
9. **Dockerfile 没有在真实镜像上构建过。** 本机 Docker Desktop 配了一个
   已经不存在的代理（127.0.0.1:7890），任何镜像都拉不下来，所以 `docker build`
   只做到了"指令结构已校验"这一步。CI 的 docker job 会在能联网的 runner 上真跑一次。
10. **迁移 V1 是事后基线，不是从一开始就有的。** V1 的内容等于原来的
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
```
