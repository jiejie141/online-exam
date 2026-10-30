package com.jiejie.exam.exam.importing;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jiejie.exam.domain.entity.Question;
import com.jiejie.exam.domain.mapper.QuestionMapper;
import com.jiejie.exam.exam.QuestionSource;
import com.jiejie.exam.web.BusinessException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 导入服务的三条硬规则：预演优先、fail-closed、重复不静默。
 *
 * <p>这个类测的不是"能不能导进去"，而是<b>出问题时会不会伤到已有数据</b>。
 * 批量导入最典型的故障不是崩溃，是"导了一半"和"悄悄把答案改了"。
 */
@ExtendWith(MockitoExtension.class)
class QuestionImportServiceTest {

    private static final long TEACHER = 7L;

    @Mock
    private QuestionMapper questionMapper;

    private QuestionImportService service;

    private static final String OPTS_AB =
            "[{\"key\":\"A\",\"text\":\"甲\"},{\"key\":\"B\",\"text\":\"乙\"}]";

    @BeforeEach
    void setUp() {
        service = new QuestionImportService(questionMapper);
    }

    /**
     * LambdaQueryWrapper 靠 TableInfo 缓存把 {@code Question::getStem} 解析成列名。
     * 这份缓存在 Spring 启动时由 MyBatis 填；纯单元测试没有那一步，
     * 不手工初始化的话第一个 {@code eq()} 就抛
     * "can not find lambda cache for this entity" —— 报错信息看起来像 MP 坏了，
     * 实际只是少了初始化。
     */
    @BeforeAll
    static void initLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                Question.class);
    }

    private static String line(String stem, String answer) {
        return "{\"subject\":\"数学\",\"type\":\"single\",\"stem\":\"" + stem + "\","
                + "\"options\":" + OPTS_AB + ",\"answer\":\"" + answer + "\"}";
    }

    private ImportReport run(String jsonl, boolean dryRun) {
        return service.importJsonl(TEACHER, jsonl, QuestionSource.CEVAL, 5, null, dryRun);
    }

    private void existing(Question... questions) {
        when(questionMapper.selectList(any())).thenReturn(List.of(questions));
    }

    private static Question stored(String stem, String hash, String answer) {
        Question q = new Question();
        q.setId(1L);
        q.setSubject("数学");
        q.setType("single");
        q.setStem(stem);
        q.setOptions(OPTS_AB);
        q.setAnswer(answer);
        q.setContentHash(hash);
        q.setCreatedBy(TEACHER);
        return q;
    }

    // ---------------- 预演 ----------------

    @Test
    void 默认只预演一条都不写库() {
        existing();
        ImportReport r = run(line("一加一等于二", "A") + "\n" + line("二加二等于四", "B"), true);

        assertTrue(r.dryRun());
        assertEquals(2, r.total());
        assertEquals(2, r.inserted(), "预演也要报出\"将会入库多少条\"，否则预演没有意义");
        // 这是整个功能最重要的一条断言：预演真的一个字都没写
        verify(questionMapper, never()).insert(any(Question.class));
    }

    @Test
    void 真导入时逐条写入并把归属写成令牌里的老师() {
        existing();
        ImportReport r = run(line("一加一等于二", "A"), false);

        assertEquals(1, r.inserted());
        ArgumentCaptor<Question> captor = ArgumentCaptor.forClass(Question.class);
        verify(questionMapper).insert(captor.capture());
        assertEquals(TEACHER, captor.getValue().getCreatedBy(),
                "归属必须来自令牌；前端如果能指定 created_by，题库隔离就形同虚设");
        assertEquals("ceval", captor.getValue().getSource());
        assertEquals(64, captor.getValue().getContentHash().length());
    }

    // ---------------- fail-closed ----------------

    @Test
    void 有坏数据时真导入整批拒绝且一条不写() {
        // 刻意不 stub selectList：这条路径在查库之前就该停住。
        // 如果哪天实现改成"先查再校验"，这个测试会立刻失败 —— 那正是我要的提醒
        String jsonl = line("一加一等于二", "A") + "\n{坏行\n" + line("二加二等于四", "B");

        BusinessException e = assertThrows(BusinessException.class, () -> run(jsonl, false));
        assertTrue(e.getMessage().contains("第 2 行"), e.getMessage());
        assertTrue(e.getMessage().contains("整批未写入"), e.getMessage());
        // 不做"好的先进、坏的报出来"：导 5000 条漏 3 条，
        // 库里是哪 4997 条没人说得清
        verify(questionMapper, never()).insert(any(Question.class));
    }

    @Test
    void 同样的坏数据在预演时只报告不抛错() {
        existing();
        String jsonl = line("一加一等于二", "A") + "\n{坏行";

        ImportReport r = run(jsonl, true);
        assertFalse(r.clean());
        assertEquals(1, r.issues().size());
        assertEquals(2, r.issues().get(0).lineNo());
        verify(questionMapper, never()).insert(any(Question.class));
    }

    @Test
    void 预演报的是可入库条数而不是零() {
        existing();
        ImportReport r = run(line("一加一等于二", "A") + "\n{坏行", true);
        // inserted 报的是"可入库条数"，clean() 才表示"这批能不能导"。
        // 两者分开报，才能回答"除了这 1 条坏的，其余能不能进"
        assertEquals(1, r.inserted());
        assertFalse(r.clean());
    }

    // ---------------- 去重与冲突 ----------------

    @Test
    void 库里已有同题同答案时算重复而不是再插一条() {
        String jsonl = line("一加一等于二", "A");
        String hash = ContentHash.of("single", "一加一等于二", OPTS_AB);
        existing(stored("一加一等于二", hash, "A"));

        ImportReport r = run(jsonl, false);

        assertEquals(0, r.inserted());
        assertEquals(1, r.duplicate());
        assertTrue(r.conflicts().isEmpty());
        verify(questionMapper, never()).insert(any(Question.class));
    }

    @Test
    void 库里已有同题但答案不同时报冲突且绝不覆盖() {
        // 覆盖意味着"已经按旧答案判过的卷"和新答案之间悄悄产生了矛盾 ——
        // 这种事只能由人来裁决
        String jsonl = line("一加一等于二", "B");
        String hash = ContentHash.of("single", "一加一等于二", OPTS_AB);
        existing(stored("一加一等于二", hash, "A"));

        ImportReport r = run(jsonl, false);

        assertEquals(0, r.inserted());
        assertEquals(1, r.conflicting());
        assertEquals("A", r.conflicts().get(0).existingAnswer());
        assertEquals("B", r.conflicts().get(0).incomingAnswer());
        verify(questionMapper, never()).updateById(any(Question.class));
        verify(questionMapper, never()).insert(any(Question.class));
    }

    @Test
    void 选项顺序不同但选中内容相同时不算冲突() {
        // 同一道题，库里排 [甲,乙] 答案 A（=甲），导入的排 [乙,甲] 答案 B（=甲）。
        // 字母不同、内容相同 —— 按字母比会报一个根本不存在的冲突
        String swapped = "[{\"key\":\"A\",\"text\":\"乙\"},{\"key\":\"B\",\"text\":\"甲\"}]";
        String jsonl = "{\"subject\":\"数学\",\"type\":\"single\",\"stem\":\"一加一等于二\","
                + "\"options\":" + swapped + ",\"answer\":\"B\"}";
        String hash = ContentHash.of("single", "一加一等于二", OPTS_AB);
        existing(stored("一加一等于二", hash, "A"));

        ImportReport r = run(jsonl, false);

        assertEquals(1, r.duplicate());
        assertTrue(r.conflicts().isEmpty());
    }

    @Test
    void 同一个文件里出现两次同一道题时第二次算重复() {
        existing();
        String one = line("一加一等于二", "A");
        ImportReport r = run(one + "\n" + one, false);

        assertEquals(1, r.inserted());
        assertEquals(1, r.duplicate());
        verify(questionMapper, times(1)).insert(any(Question.class));
    }

    @Test
    void 并发导入撞唯一索引时算重复而不是500() {
        // 先查后插在并发下必然漏：两个请求同时查到"不存在"然后都插。
        // 唯一索引挡住第二个，这里必须把它记成 duplicate
        existing();
        when(questionMapper.insert(any(Question.class))).thenThrow(new DuplicateKeyException("uk_question_owner_hash"));

        ImportReport r = run(line("一加一等于二", "A"), false);

        assertEquals(0, r.inserted());
        assertEquals(1, r.duplicate());
    }

    // ---------------- 历史指纹回填 ----------------

    @Test
    void 历史题的指纹会被回填并参与本次去重() {
        // V4 之前的题 content_hash 是 NULL，而 NULL 在唯一索引里互不相等 ——
        // 不回填的话，把以前手敲的题再导一次，题库里就变成两份
        String stem = "一加一等于二";
        Question old = stored(stem, null, "A");
        existing(old);

        ImportReport r = run(line(stem, "A"), false);

        assertEquals(1, r.backfilled());
        assertEquals(1, r.duplicate(), "回填之后这一条必须被判为已存在");
        assertEquals(0, r.inserted());
        verify(questionMapper).updateById(any(Question.class));
        verify(questionMapper, never()).insert(any(Question.class));
        assertEquals(64, old.getContentHash().length());
    }

    @Test
    void 预演时报出的回填条数与真跑一致() {
        // 预演和真跑结论不一样，等于没预演
        existing(stored("一加一等于二", null, "A"));
        ImportReport r = run(line("一加一等于二", "A"), true);
        assertEquals(1, r.backfilled());
        assertEquals(1, r.duplicate());
        verify(questionMapper, never()).updateById(any(Question.class));
    }

    /**
     * 报告是给机器读的（前端）、也是给人读的（错误信息），所以它的 JSON 形状是契约。
     *
     * <p>这条用例是被实机跑出来的 bug 逼出来的：{@code clean()} 和
     * {@code conflicting()} 是 record 的<b>派生方法</b>而不是组件，
     * Jackson 默认不序列化它们 —— 接口返回 200、日志全绿，
     * 而前端读到的 {@code clean} 是 undefined，
     * 于是「确认导入」按钮永远点不动。
     */
    @Test
    void 报告的派生字段必须出现在JSON里() throws Exception {
        existing();
        ImportReport r = run(line("一加一等于二", "A"), true);

        String json = new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(r);

        assertTrue(json.contains("\"clean\""), "clean 必须被序列化: " + json);
        assertTrue(json.contains("\"conflicting\""), "conflicting 必须被序列化: " + json);
        assertTrue(json.contains("\"dryRun\""));
        assertTrue(json.contains("\"issuesTruncated\""));
    }

    @Test
    void 回填时撞唯一索引的历史题保持原样() {
        // 历史数据里本来就有两道内容相同的题，回填会撞唯一键。
        // 删哪一条是人的决定，不是导入流程的决定
        Question bad = stored("一加一等于二", null, "A");
        existing(bad);
        when(questionMapper.updateById(any(Question.class))).thenThrow(new DuplicateKeyException("uk_question_owner_hash"));

        ImportReport r = run(line("另一道完全不同的题", "A"), false);

        assertEquals(0, r.backfilled());
        assertNull(bad.getContentHash(), "回填失败必须把字段还原成 NULL，而不是留一个没写进库的值");
    }
}
