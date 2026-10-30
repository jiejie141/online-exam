package com.jiejie.exam.exam.importing;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jiejie.exam.domain.entity.Question;
import com.jiejie.exam.domain.mapper.QuestionMapper;
import com.jiejie.exam.exam.QuestionSource;
import com.jiejie.exam.grading.GradingRule;
import com.jiejie.exam.web.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 题库批量导入。
 *
 * <p><b>为什么导入是"一次批处理"而不是"运行时从文件/数据集加载"</b>：
 * 题库是长期资产，必须和考试记录、答卷放在同一个事务边界里（判分要读它、
 * 组卷要筛它、报表要按它统计）。放在文件或内存里，一旦服务重启、
 * 换一台机器部署，"题目 id" 就不再指向同一道题 —— 而答卷里存的全是题目 id。
 *
 * <p>所以分工是：<b>导入是离线的一次性动作，运行期只做查询和分页。</b>
 * 这也是为什么这里没有缓存、没有定时重载 —— 那属于"题库会频繁变更"的系统，
 * 考试系统不是。
 *
 * <p><b>三条硬规则</b>：
 * <ol>
 *   <li><b>先全部校验，再写库</b>（fail-closed）。只要有任意一条不合法，
 *       整批一条都不写，返回带行号的问题清单。不做"能进的先进、坏的跳过" ——
 *       那样导 5000 条漏 3 条，库里是哪 4997 条没人说得清，
 *       而"我以为导完了"是这个功能最典型的失败方式。</li>
 *   <li><b>默认只预演</b>（dryRun=true）。写库必须显式 {@code dryRun=false}。
 *       批量操作里，"默认安全"比"默认高效"重要得多。</li>
 *   <li><b>重复不静默</b>。答案一致的重复算 duplicate（正常，重跑时全是这个）；
 *       答案不一致的算 conflict，逐条列出来让人裁决。<b>绝不覆盖已有答案</b> ——
 *       覆盖意味着"已经按旧答案判过的卷"和"新答案"之间悄悄产生了矛盾。</li>
 * </ol>
 */
@Service
public class QuestionImportService {

    private static final Logger log = LoggerFactory.getLogger(QuestionImportService.class);

    /** 报告里问题明细的上限 */
    static final int MAX_ISSUES = 50;
    /** 抽样核对条数 */
    static final int SAMPLE_SIZE = 5;
    /** 错误信息里最多列几条（够定位就行，全量在 dryRun 的报告里） */
    private static final int ISSUES_IN_MESSAGE = 5;
    /** 单次请求体上限（字符数），见 importJsonl 里的说明 */
    static final int MAX_INPUT_CHARS = 8_000_000;

    private final QuestionMapper questionMapper;

    public QuestionImportService(QuestionMapper questionMapper) {
        this.questionMapper = questionMapper;
    }

    /**
     * 导入一批 JSONL 题目。
     *
     * @param teacherId    题目归属，来自令牌，不接受前端指定
     * @param jsonl        每行一个 JSON 对象
     * @param source       批内缺省来源（行内写了 source 则以行为准）
     * @param defaultScore 行内缺 score 时的兜底分值
     * @param license      行内缺 license 时的兜底许可证
     * @param dryRun       true = 只预演，不写库
     */
    @Transactional
    public ImportReport importJsonl(long teacherId, String jsonl, QuestionSource source,
                                    int defaultScore, String license, boolean dryRun) {
        if (defaultScore <= 0 || defaultScore > 100) {
            throw new BusinessException("默认分值必须在 1..100 之间: " + defaultScore);
        }
        // 请求体上限。5000 道题 × 每道约 1 KB ≈ 5 MB，8 MB 留了余量。
        // 不设这一条的话，一个 200 MB 的请求会先被完整读进内存再被拒绝，
        // 而 Xmx 通常也就 512 MB —— 这个接口能被打成 OOM 的入口。
        if (jsonl != null && jsonl.length() > MAX_INPUT_CHARS) {
            throw new BusinessException("导入内容超过 " + (MAX_INPUT_CHARS / 1_000_000)
                    + " MB 上限，请拆成多个文件分批导入");
        }

        QuestionJsonlParser.ParseResult parsed =
                new QuestionJsonlParser(source, defaultScore, license).parse(jsonl);

        int total = parsed.drafts().size() + parsed.issues().size();
        List<ImportReport.Issue> issues = parsed.issues();
        boolean truncated = issues.size() > MAX_ISSUES;
        if (truncated) {
            issues = List.copyOf(issues.subList(0, MAX_ISSUES));
        }

        // 真导入时有坏数据就直接停。这里不做"先写好的、坏的报出来"，
        // 理由见类注释第 1 条。错误信息里带行号，而且明说要先用 dryRun 看全量。
        if (!dryRun && !parsed.issues().isEmpty()) {
            throw new BusinessException("有 " + parsed.issues().size()
                    + " 条数据未通过校验，整批未写入。"
                    + firstIssues(parsed.issues())
                    + " 先用 dryRun=true 拿到完整报告，改完再导。");
        }

        // ---- 载入这个老师已有的题（只取判重与比对需要的列）----
        // 为什么一次全取而不是逐条查：6000 道题逐条 select 是 6000 次往返，
        // 而这道查询只走 idx_question_owner_pick 的前缀（created_by），
        // 一次拿回来几百 KB。真正的天花板在几千题这个量级，
        // 到那时候应该改成"临时表 + INSERT ... SELECT"，而不是继续调这里。
        List<Question> existing = questionMapper.selectList(
                new LambdaQueryWrapper<Question>()
                        .eq(Question::getCreatedBy, teacherId)
                        .select(Question::getId, Question::getType, Question::getStem,
                                Question::getOptions, Question::getAnswer, Question::getContentHash));

        Map<String, Question> byHash = new LinkedHashMap<>();
        List<Question> noHash = new ArrayList<>();
        for (Question q : existing) {
            if (q.getContentHash() == null || q.getContentHash().isBlank()) {
                noHash.add(q);
            } else {
                byHash.putIfAbsent(q.getContentHash(), q);
            }
        }

        // ---- 回填历史指纹（V4 之前的题没有 content_hash）----
        int backfilled = backfill(teacherId, noHash, byHash, dryRun);

        // ---- 批内去重：同一批里出现两次同一道题，第二次算 duplicate ----
        Map<String, QuestionDraft> unique = new LinkedHashMap<>();
        for (QuestionDraft d : parsed.drafts()) {
            if (unique.containsKey(d.contentHash())) {
                // 批内重复：同一个文件里这两行内容一样。不报 conflict，
                // 因为它俩还不是"库里的答案"和"新答案"的关系，
                // 顶多是同一个源里重复了一遍。等写库时唯一索引也会挡住。
                continue;
            }
            unique.put(d.contentHash(), d);
        }

        int inserted = 0;
        int duplicate = parsed.drafts().size() - unique.size();
        List<ImportReport.Conflict> conflicts = new ArrayList<>();
        List<QuestionDraft> candidates = new ArrayList<>();

        for (QuestionDraft d : unique.values()) {
            Question hit = byHash.get(d.contentHash());
            if (hit == null) {
                candidates.add(d);
                continue;
            }
            if (sameAnswer(hit, d)) {
                duplicate++;
            } else {
                conflicts.add(new ImportReport.Conflict(
                        d.lineNo(), d.stemBrief(), hit.getAnswer(), d.answer()));
            }
        }

        if (dryRun) {
            inserted = candidates.size();
        } else {
            for (QuestionDraft d : candidates) {
                try {
                    questionMapper.insert(toEntity(d, teacherId));
                    inserted++;
                } catch (DuplicateKeyException e) {
                    // 并发导入的最终兜底：两个请求同时查到"不存在"然后都插。
                    // 唯一索引挡住第二个，这里把它记成 duplicate 而不是 500。
                    duplicate++;
                }
            }
        }

        List<ImportReport.Sample> samples = candidates.stream()
                .limit(SAMPLE_SIZE)
                .map(d -> new ImportReport.Sample(d.lineNo(), d.subject(), d.type(),
                        d.stemBrief(), d.answer()))
                .toList();

        log.info("question import teacher={} dryRun={} total={} inserted={} duplicate={} conflicts={}",
                teacherId, dryRun, total, inserted, duplicate, conflicts.size());

        return new ImportReport(dryRun, total, inserted, duplicate, conflicts,
                backfilled, issues, truncated, samples);
    }

    /**
     * 给历史题补算指纹。
     *
     * <p>为什么需要这一步：V4 之前入库的题 {@code content_hash} 是 NULL，
     * 而 NULL 在唯一索引里互不相等 —— 也就是说<b>它们永远不参与去重</b>。
     * 不回填的话，老师把以前手敲的题再导一次，题库里就会变成两份，
     * 组卷抽中两次，学生在一张卷子上看到同一道题两遍。
     *
     * <p>dryRun 下只统计"将回填多少条"，同时把算出的指纹放进 {@code byHash}，
     * 这样预演结果和真跑结果一致 —— 预演和真跑结论不一样，等于没预演。
     *
     * @return 回填（或将回填）的条数
     */
    private int backfill(long teacherId, List<Question> noHash, Map<String, Question> byHash,
                         boolean dryRun) {
        int done = 0;
        for (Question q : noHash) {
            String hash;
            try {
                hash = ContentHash.of(q.getType(), q.getStem(), q.getOptions());
            } catch (RuntimeException e) {
                // 历史脏题（选项不是合法 JSON、答案不在选项里……）算不出指纹。
                // 跳过而不是让整次导入失败：这类题本来也进不了卷子 ——
                // addQuestions 会再验一次 QuestionRule，它会在组卷时被拦下。
                log.warn("skip hash backfill for question id={} : {}", q.getId(), e.getMessage());
                continue;
            }
            q.setContentHash(hash);
            if (!dryRun) {
                try {
                    questionMapper.updateById(q);
                } catch (DuplicateKeyException e) {
                    // 历史数据里本来就有两道内容相同的题，回填会撞唯一键。
                    // 保持 NULL 不动 —— 删哪一条是人的决定，不是导入流程的决定。
                    log.warn("question id={} duplicate after backfill, left hash null", q.getId());
                    q.setContentHash(null);
                    continue;
                }
            }
            // 放进 byHash：让本次导入能识别出"这道题库里已经有过了"。
            // 已有同 hash 的行优先于刚回填的行（putIfAbsent 的顺序由前面决定）
            byHash.putIfAbsent(hash, q);
            done++;
        }
        return done;
    }

    /**
     * 判断"库里的这条"和"要导的这条"是不是同一个答案。
     *
     * <p>选择题<b>不比字母，比选中的选项文本</b>。原因：两个来源的选项顺序常常不同，
     * 同一道题 A 来源是 [甲,乙] 答案 A、B 来源是 [乙,甲] 答案 B ——
     * 字母不同但指的是同一个选项。按字母比会产出一个根本不存在的"答案冲突"，
     * 而假冲突多了以后，真冲突就没人看了。
     *
     * <p>判断题没有选项，直接比归一化后的 T/F。
     * 文本解析不出（选项 JSON 坏了）时退回按字母比 —— 那种题本身也进不了卷子，
     * 由 {@code addQuestions} 的 {@code QuestionRule} 拦下。
     */
    private static boolean sameAnswer(Question existing, QuestionDraft draft) {
        if (draft.options() != null && existing.getOptions() != null) {
            String a = ContentHash.selectedTexts(existing.getOptions(), existing.getAnswer());
            String b = ContentHash.selectedTexts(draft.options(), draft.answer());
            if (a != null && b != null) {
                return a.equals(b);
            }
        }
        return Objects.equals(GradingRule.normalize(existing.getAnswer()),
                GradingRule.normalize(draft.answer()));
    }

    private static Question toEntity(QuestionDraft d, long teacherId) {
        Question q = new Question();
        q.setSubject(d.subject());
        q.setType(d.type());
        q.setStem(d.stem());
        q.setOptions(d.options());
        q.setAnswer(d.answer());
        q.setScore(d.score());
        q.setDifficulty(d.difficulty());
        q.setExternalId(d.externalId());
        q.setSource(d.source());
        q.setSourceRef(d.sourceRef());
        q.setLicense(d.license());
        q.setContentHash(d.contentHash());
        q.setCreatedBy(teacherId);
        return q;
    }

    private static String firstIssues(List<ImportReport.Issue> issues) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(ISSUES_IN_MESSAGE, issues.size()); i++) {
            ImportReport.Issue issue = issues.get(i);
            sb.append(i == 0 ? "" : "；")
                    .append(issue.lineNo() > 0 ? "第 " + issue.lineNo() + " 行: " : "")
                    .append(issue.reason());
        }
        return sb.toString();
    }
}
