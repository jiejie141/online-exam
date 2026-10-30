# 接入真实题库：数据来源、格式、导入与抽题

> 对应代码：`V4__question_source_and_dedup.sql`、`exam/importing/*`、`exam/AutoComposeService.java`、
> `web/src/views/teacher/QuestionBankView.vue`、`scripts/convert_question_bank.py`
>
> 测试：后端 **260** 项（原 209 + 新增 51），全绿。`mvn -B -o test`

---

## 0. 一句话

把「真实题库」当成**一次性的离线导入**，不当成运行时的外部数据源：
外部格式由**仓库外的脚本**转换，Java 只认一种中间格式（JSONL），
写库前先跑一遍**预演**，重复与答案冲突由**数据库指纹**裁定，抽题按**配额**随机。

这条链路上每一个决定都是为了同一个目标：**出问题的时候能定位到第几行**。

---

## 1. 数据来源与获取方式

### 1.1 有什么是能用的

| 来源 | 真实度 | 带答案 | 获取 | 许可证 | 能不能用 |
|---|---|---|---|---|---|
| **C-Eval**（52 学科，中文，约 1.3 万题） | 真实考题风格 | 是 | GitHub `hkust-nlp/ceval` → `data/val/*.csv` | CC BY-NC-SA 4.0 | 课程/作业/演示 ✅；商用 ❌ |
| **CMMLU**（67 学科，中文） | 真实考题风格 | 是 | GitHub `haonan-li/CMMLU` | CC BY-NC-SA 4.0 | 同上 |
| **MMLU**（57 学科，英文，约 1.4 万题） | 真实考题风格 | 是 | GitHub `hendrycks/test` → `data/val/*.csv` | MIT | ✅（署名） |
| **院系/课程授权题库** | 真实 | 通常有 | 走教学办或任课老师 | 按合同 | ✅ 最优 |
| **任课老师自建** | 真实 | 是 | Excel/CSV | 自有 | ✅ |
| 商业题库网站（某星、某通、某笔） | 真实 | 有 | 爬取 | 版权归平台 | ❌ **不做** |
| 扫描教辅/试卷图片 OCR | 真实 | 部分 | — | 版权归出版社 | ❌ **不做** |

### 1.2 明确划一条线

**我不会帮你爬商业题库，也不会把有版权的试卷 OCR 进库。** 这不是保守：
这类数据一旦进库，它就会出现在「题库管理」页、出现在截图里、出现在简历附件里，
而你无法在事后把它摘干净。相比之下，C-Eval 这种有明确许可证的数据集，
既能满足"真实题量"的演示需求，又能一句话说清来源。

### 1.3 许可证要落在数据上，不是落在文档里

`question.license` 这一列不是装饰。它决定这条数据能不能出现在对外演示里。
导入时每条记录都会写入许可证，于是"哪些题不能拿去截图"这个问题变成一条 SQL：

```sql
SELECT source, license, COUNT(*) FROM question
WHERE license IS NULL OR license NOT LIKE '%NC%'
GROUP BY source, license;
```

### 1.4 获取方式（以 C-Eval 为例）

```bash
# 1. 拿数据（非商用，请遵守其许可）
git clone --depth 1 https://github.com/hkust-nlp/ceval.git ~/ceval
ls ~/ceval/data/val | head        # 每学科一个 csv：computer_network_val.csv …

# 2. 转成本项目的中间格式（先限量跑通，别一次全转）
python scripts/convert_question_bank.py ceval \
    --src ~/ceval/data/val --out ~/ceval.jsonl --limit 300

# 3. 导进系统（先预览）
#    题库管理 → 批量导入 → 粘贴 ~/ceval.jsonl 的内容 → 预览 → 确认导入

# 4. 核对
mysql -uroot -proot online_exam -e \
  "SELECT source, COUNT(*) FROM question GROUP BY source;"
```

> **别把转出来的 `.jsonl` 提交进仓库。** 它不是代码，是第三方数据，
> 放进公开仓库等于重新分发，会额外触发 CC BY-NC-SA 的「相同方式共享」义务。
> 放在 `~/` 或 `var/` 下。

---

## 2. 数据结构与字段设计

### 2.1 中间格式（导入器唯一认的格式）

一行一个 JSON 对象，字段名有别名容错，但**语义固定**：

```json
{
  "subject": "计算机网络",
  "type": "single",
  "stem": "TCP 三次握手的第二步是？",
  "options": [{"key":"A","text":"SYN"},{"key":"B","text":"SYN+ACK"}],
  "answer": "B",
  "score": 5,
  "difficulty": 1,
  "external_id": "computer_network-23",
  "source": "ceval",
  "source_ref": "computer_network_val.csv 第 24 行",
  "license": "CC BY-NC-SA 4.0"
}
```

被接受的写法（真实的来源就是这么杂，不接受只是没必要的摩擦）：

| 字段 | 也接受 | 说明 |
|---|---|---|
| `subject` | `course` `discipline` `科目` | 必填，≤64 字 |
| `stem` | `question` `text` `content` `题干` | 必填，**≤1000 字**（库字段上限） |
| `type` | `kind` `题型` | `single` / `multiple` / `judge`；**缺失时按形状推断** |
| `options` | `choices` `option_list` | `[{key,text}]` / `["甲","乙"]` / `{"A":"甲"}` 三种 |
| `answer` | `correct` `answer_key` `答案` | 字母串 / 数组 / 数字下标（**1 起**）/ 判断题中文 |
| `difficulty` | `level` | **只能是 1 / 2 / 3** |
| `external_id` | `id` `qid` | 源系统里的题目 ID，用于回溯 |
| `source` | `from` | 枚举，见 `QuestionSource` |

### 2.2 表结构新增（V4）

| 列 | 类型 | 为什么需要 |
|---|---|---|
| `external_id` | `VARCHAR(128) NULL` | 出了问题（答案错、题干有争议）要能回到源头那一条 |
| `source` | `VARCHAR(16) NOT NULL DEFAULT 'manual'` | 版权盘查：一条 SQL 查出所有非自建题 |
| `source_ref` | `VARCHAR(255) NULL` | 数据集版本 / 文件名+行号 / 授权合同号 |
| `license` | `VARCHAR(64) NULL` | 决定这条数据能不能出现在对外演示里。NULL = 未知 ≠ 可随便用 |
| `content_hash` | `CHAR(64) NULL` | 去重指纹。**允许 NULL**：V4 之前的历史题算不出与 Java 一致的值 |

索引：

```sql
UNIQUE KEY uk_question_owner_hash (created_by, content_hash)  -- 并发导入的最终兜底
KEY idx_question_owner_pick (created_by, subject, type, difficulty)  -- 自动组卷
```

两个刻意的决定写在迁移文件里，这里只重复结论：

- **唯一键带 `created_by`**：题库是老师的私有资源，同一道题出现在两个老师库里是正常的。
  只按 `content_hash` 全局唯一，会让 B 老师永远导不进那道题，还报"重复"。
- **`content_hash` 允许 NULL**：MySQL 唯一索引允许多个 NULL，所以历史行不会互相打架；
  导入时先回填一次指纹（`QuestionImportService#backfill`），历史数据就能参与去重。

### 2.3 指纹算什么

```
sha256( type + \u0001 + 归一化题干 + \u0001 + 排序后的选项文本 )
```

- 归一化：NFKC（全角→半角）→ 小写 → 去空白去标点
- 选项**只取文本、不含 key**：key 只是标签，换了位置还是同一道题
- **不含 answer**：这是刻意的。同一道题被录了两个答案时，必须能被识别成
  「答案冲突」交给人裁决；如果答案参与哈希，两条会变成两道互不相干的题各自入库，
  组卷抽到错的那份就是全班判错。

---

## 3. 题库加载与存储方案

**结论：不做运行时加载。** 题库是长期资产，必须和答卷、判分在同一个事务边界里
（判分要读它、组卷要筛它、报表按它统计）。放在文件或内存里，服务一重启、
换一台机器部署，"题目 id" 就不再指向同一道题 —— 而答卷里存的全是题目 id。

| 环节 | 做法 | 为什么 |
|---|---|---|
| 入口 | `POST /teacher/questions/import`，**`dryRun` 默认 `true`** | 批量操作的默认值应该是"什么都不做" |
| 解析 | 逐行解析，坏行只产生一条带行号的 issue | 一条坏数据不该让 4999 条好数据白解析 |
| 校验 | **整批 fail-closed**：有一条不合法就一条都不写 | "导 5000 条漏 3 条"时，库里是哪 4997 条没人说得清 |
| 去重 | 批内 Map + 库里 `content_hash` 集合 | 同一批里重复、跨批重复都能识别 |
| 写库 | 单事务逐条 insert，`DuplicateKeyException` 记为 duplicate | 并发导入时"先查后插"必然漏，唯一索引兜底 |
| 历史数据 | 导入前回填 `content_hash IS NULL` 的行 | 不回填 → 历史题永远不参与去重 → 二次导入变两份 |
| 上限 | 单次 5000 行 / 8 MB | 不是技术限制，是"出了问题能人工过一遍"的上限 |

**为什么不做批量 `INSERT ... VALUES (...),(...)`**：那需要自己写 Mapper 方法，
且拿不到可靠的自增 ID 语义。5000 条逐条 insert 在一个事务里约 2–3 秒，够用；
真到几万条应该改成临时表 + `INSERT ... SELECT`，而不是继续压这个接口。

---

## 4. 抽题与答案校验逻辑

### 4.1 抽题：按配额，不按纯随机

```
POST /teacher/exams/{examId}/auto-compose
{ "subject": "计算机网络",
  "quotas": [ {"type":"single","difficulty":1,"count":10},
              {"type":"multiple","difficulty":null,"count":5} ],
  "score": 5 }
```

| 决定 | 理由 |
|---|---|
| 只 `SELECT id`，内存 `shuffle` 取前 N | `ORDER BY RAND()` 走不了索引、每次要物化全表；几千个 id 只有几十 KB |
| 配额不足 → 报错，不降级 | 少抽 3 道，卷面总分就和老师预期不符，而总分是 publish 时算出来的，错误会传到成绩统计里 |
| 过滤已抽中的 id | "单选简单 2 道"+"单选不限 2 道"两次查询大量重叠，重复抽中会被 `uk_exam_question` 静默跳过，总数就少了 |
| 不自己写校验，最后交给 `addQuestions` | 归属、"只有草稿能改卷"、分值正数、题目自洽都在那边；抽题再写一遍，就一定会有一天两份实现不一样 |

### 4.2 答案校验的三个层次

| 层 | 位置 | 管什么 |
|---|---|---|
| 解析层 | `QuestionJsonlParser` | 选项 key 重排成 A–Z、答案随之映射、数字下标 1 起、判断题中文→T/F、题干长度、难度 1–3 |
| 入库层 | `QuestionRule`（已有） | 选项 ≥2、key 不重复、答案必须在选项里、判断题不能带选项 |
| 判分层 | `GradingRule`（已有） | 单选/判断完全一致；多选全对满分、漏选半分、错选 0 分、未答 0 分 |

**为什么 key 必须重排**：`GradingRule` 按**单字符**比对答案。
一个 key 是 `"①"` 或 `"选项一"` 的题库，学生提交的答案永远拼不出能对上的集合。
重排后 answer 同步映射，语义不变，`external_id` 保留所以回溯不受影响。

**为什么数字答案只接受 1 起**：0 起/1 起差一位，后果是整场考试所有单选题
都指向邻项 —— 全班分数集体偏移，且没有任何报错。与其猜，不如让
`convert_question_bank.py` 把下标转成字母后再导。解析器遇到 `0` 会直接拒绝并说明原因。

**为什么答案比对按"选项文本"而不是字母**：两个来源的选项顺序常常不同。
同一道题 A 来源 `[甲,乙]` 答案 `A`、B 来源 `[乙,甲]` 答案 `B` —— 字母不同但指的是同一个选项。
按字母比会产生一个根本不存在的"答案冲突"，而假冲突多了以后真冲突就没人看了。

---

## 5. 新增 / 调整的模块

### 新增

| 文件 | 作用 |
|---|---|
| `db/migration/V4__question_source_and_dedup.sql` | 5 个新列 + 唯一键 + 抽题索引，逐项幂等 |
| `exam/QuestionSource.java` | 来源枚举（取值封闭，便于版权盘查） |
| `exam/importing/ContentHash.java` | 归一化 SHA-256 指纹 + 答案文本比对 |
| `exam/importing/QuestionDraft.java` | 中间表示（带行号，实体做不到） |
| `exam/importing/ImportReport.java` | 报告：inserted / duplicate / conflicts / issues / samples |
| `exam/importing/QuestionJsonlParser.java` | JSONL 解析 + 字段别名 + 宽容与严格的分界 |
| `exam/importing/QuestionImportService.java` | 预演、fail-closed、去重、冲突识别、指纹回填 |
| `exam/AutoComposeService.java` | 按配额抽题，委托 `addQuestions` 落库 |
| `scripts/convert_question_bank.py` | 外部格式 → JSONL（C-Eval / MMLU / 任意 CSV），纯标准库 |
| `docs/question-bank/sample.jsonl` | 10 道自建示例（无版权问题），被测试引用 |
| `docs/question-bank/sample-broken.jsonl` | 6 行、5 类错误，用来演示报告 |

### 调整

| 文件 | 改动 |
|---|---|
| `Question.java` | 加 5 个字段 |
| `TeacherController.java` | 加 `POST /questions/import`、`POST /exams/{id}/auto-compose` |
| `ExamService.createQuestion` | 难度上限补到 3（原来只判下界） |
| `web/src/api/index.js` | 加 `importQuestions`、`autoCompose` |
| `QuestionBankView.vue` | 「批量导入」按钮 + 预览/确认两步对话框 + 报告渲染；难度上限 5→3 |
| `ExamManageView.vue` | 「自动组卷」入口 + 配额表 |
| `db/schema.sql` | 同步新增列与索引（手工建库路径不能和 Flyway 分叉） |

### 顺带修掉的一个既有问题

页面上的难度输入框 `:max="5"`，而库注释、判分、抽题都按 **1 易 2 中 3 难**。
填 4/5 建出来的题能入库，但自动组卷按 1/2/3 筛，**这类题只有"不限难度"时才可能被抽中**，
而且看不出哪里不对。现在三处口径统一到 1–3。

---

## 6. 约束与风险

| 约束 | 具体表现 | 处置 |
|---|---|---|
| **版权** | C-Eval/CMMLU 是 CC BY-NC-SA（禁商用、需署名、相同方式共享）；商业题库不可爬 | 每条记录写 `license`；转换产物不进仓库；对外演示前用 SQL 盘查 |
| **答案准确性** | 真实数据集里确有错题，尤其多义/时效性题目。**这是最大的风险，且无法自动消除** | 导入报告给 5 条抽样供人工核对；保留 `source_ref` 以便回溯；答案冲突不覆盖，交人裁决 |
| **格式兼容** | 列名/分隔符/编码/选项编号千差万别 | 脚本负责认格式，Java 只认 JSONL；别名表 + 三种选项写法 + 两种答案写法 |
| **0 基下标** | MMLU 多数转写版本是 0–3 | 转换脚本显式 `zero_based=True`；解析器拒绝 `0` 并说明原因 |
| **题干长度** | 材料题常超 1000 字，MySQL `VARCHAR(1000)` 会在 insert 时才炸，且不告诉你是第几行 | 解析阶段就拦，报"第 N 行：题干 1203 字，超过 1000 字" |
| **难度口径** | 别的系统常用 1–5 | 只接受 1–3，报错里列全合法值 |
| **性能上限** | 一次导入 5000 条 / 老师题库几千条 | 逐条 insert + 一次载入已有指纹；再往上要改临时表方案 |
| **重复题** | 同一题不同来源写法不同 | 归一化指纹；文本比对答案；冲突列出来而不是覆盖 |

---

## 7. 实现步骤（照着做）

```bash
# 1. 迁移（会自动跑，Flyway 在启动时执行 V4）
mvn -B -o test                       # 应为 260 项全绿
docker start exam-mysql exam-redis exam-rabbitmq   # 或 docker compose up -d
mvn spring-boot:run

# 2. 拿数据（示例：C-Eval，非商用许可）
python scripts/convert_question_bank.py ceval --src ~/ceval/data/val --out /tmp/q.jsonl --limit 200

# 3. 登录拿令牌
curl -s localhost:8081/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"teacher1","password":"..."}' | jq -r .data.token

# 4. 预演（dryRun 默认 true，这里显式写出来是为了让围观的人放心）
jq -Rn --rawfile t /tmp/q.jsonl '{jsonl:$t, dryRun:true}' > /tmp/req.json
curl -s localhost:8081/api/teacher/questions/import \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d @/tmp/req.json | jq '.data | {total,inserted,duplicate,conflicting,clean}'

# 5. 确认无误再真导
jq -Rn --rawfile t /tmp/q.jsonl '{jsonl:$t, dryRun:false}' > /tmp/req2.json
curl -s localhost:8081/api/teacher/questions/import \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d @/tmp/req2.json | jq '.data | {inserted,duplicate,conflicting}'

# 6. 重新导一遍，验证幂等（inserted 应为 0，duplicate 应等于总数）
curl -s localhost:8081/api/teacher/questions/import \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d @/tmp/req2.json | jq '.data | {inserted,duplicate}'

# 7. 自动组卷
curl -s -X POST localhost:8081/api/teacher/exams/1/auto-compose \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"quotas":[{"type":"single","count":10},{"type":"multiple","count":5}],"score":5}' \
  | jq .data
```

页面上的等价路径：**题库管理 → 批量导入 → 预览 → 确认导入**；
**考试管理 → 自动组卷 → 调配额 → 开始抽题**。

### 验收清单

- [x] 后端 260 项测试全绿（新增 51 项：指纹 12、解析 21、导入 13、抽题 7）
- [x] `docs/question-bank/sample.jsonl` 能完整解析出 10 道题且指纹互不相同（有测试锁住）
- [x] `sample-broken.jsonl` 能复现 5 类错误、只放行 1 条好数据（有测试锁住）
- [x] 前端能构建（`npx vite build` 通过）
- [ ] **实机导入 C-Eval 数据** —— 需要你先下载数据集（受许可证约束，我不代下）

---

## 8. 还没做的（明确边界）

| 没做 | 为什么现在不做 |
|---|---|
| 判断题、多选题的**自动生成** | 真实数据集几乎全是 4 选 1；判断/多选只能靠老师自建或题目改写，改写就会引入答案错误 |
| 导入**进度条 / 异步任务** | 5000 条在 3 秒内完成，同步返回报告比"提交任务再轮询"更好用 |
| 题目**去重合并**（同一题两份保留一份） | 删哪一条是人的决定。导入只报 duplicate，不做合并 |
| **公式/图片题** | 库里 `stem` 是纯文本。数学公式需要用 LaTeX 存、前端 Katex 渲染，是另一件事 |
| 按**知识点/章节**打标签 | 需要一个标签体系，而 C-Eval 的学科粒度比"知识点"粗得多 |
| 导入**撤销** | 批量删题比"导入错了"更危险。出问题走数据库备份，不走接口 |
