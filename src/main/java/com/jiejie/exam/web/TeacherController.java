package com.jiejie.exam.web;

import com.jiejie.exam.domain.dto.ExamScoreStats;
import com.jiejie.exam.domain.dto.PageResult;
import com.jiejie.exam.domain.dto.QuestionAccuracy;
import com.jiejie.exam.domain.dto.ScoreBucket;
import com.jiejie.exam.domain.entity.Exam;
import com.jiejie.exam.domain.entity.Question;
import com.jiejie.exam.exam.AutoComposeService;
import com.jiejie.exam.exam.ExamService;
import com.jiejie.exam.exam.QuestionSource;
import com.jiejie.exam.exam.importing.ImportReport;
import com.jiejie.exam.exam.importing.QuestionImportService;
import com.jiejie.exam.security.Principal;
import com.jiejie.exam.stats.StatsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/** 老师端：出题、组卷、发布、看统计。整条路径都被 Spring Security 要求 ROLE_teacher。 */
@RestController
@RequestMapping("/api/teacher")
public class TeacherController {

    public record CreateQuestionRequest(@NotBlank String subject, @NotBlank String type,
                                        @NotBlank String stem, String options,
                                        @NotBlank String answer, Integer score, Integer difficulty) {
    }

    public record CreateExamRequest(@NotBlank String title, @NotBlank String subject,
                                    @NotNull LocalDateTime startAt, @NotNull LocalDateTime endAt,
                                    Integer durationMinutes, Integer passScore) {
    }

    public record AddQuestionsRequest(@NotEmpty List<Long> questionIds, Integer score) {
    }

    /**
     * 批量导入。
     *
     * <p>{@code dryRun} 用的是 {@link Boolean} 而不是 {@code boolean}，
     * 这样"没传"和"传了 false"能区分开 —— 而服务层的默认值是 {@code true}。
     * 如果这里用基本类型，不传就等于 false，一次手滑就把 5000 道题写进去了。
     */
    public record ImportQuestionsRequest(String jsonl, Boolean dryRun, Integer defaultScore,
                                         String source, String license) {
    }

    /** 自动组卷。subject 不传就用这场考试的科目 */
    public record AutoComposeRequest(String subject, @NotEmpty List<AutoComposeService.Quota> quotas,
                                     Integer score) {
    }

    private final ExamService examService;
    private final StatsService statsService;
    private final QuestionImportService questionImportService;
    private final AutoComposeService autoComposeService;

    public TeacherController(ExamService examService, StatsService statsService,
                             QuestionImportService questionImportService,
                             AutoComposeService autoComposeService) {
        this.examService = examService;
        this.statsService = statsService;
        this.questionImportService = questionImportService;
        this.autoComposeService = autoComposeService;
    }

    /** 出题。type 只能是 single / multiple / judge，不是就抛错（不然后面判不了分） */
    @PostMapping("/questions")
    public ApiResponse<Long> createQuestion(@Valid @RequestBody CreateQuestionRequest request,
                                            @AuthenticationPrincipal Principal me) {
        Question q = new Question();
        q.setSubject(request.subject());
        q.setType(request.type());
        q.setStem(request.stem());
        q.setOptions(request.options());
        q.setAnswer(request.answer());
        q.setScore(request.score() == null ? 5 : request.score());
        q.setDifficulty(request.difficulty() == null ? 1 : request.difficulty());
        q.setCreatedBy(me.userId());
        return ApiResponse.ok(examService.createQuestion(q));
    }

    /**
     * 批量导入题库。**默认只预演**（dryRun 未传时为 true）。
     *
     * <p>预演会完整跑一遍解析、校验、去重、答案冲突比对，只是不写库，
     * 返回一份带行号的报告。真实导入必须显式 {@code "dryRun": false}。
     *
     * <p>为什么把"安全"做成默认值：这个接口的参数是一整批数据，
     * 一旦写错，清理成本远高于少点一次按钮。批量操作的默认值应该是"什么都不做"。
     */
    @PostMapping("/questions/import")
    public ApiResponse<ImportReport> importQuestions(@RequestBody ImportQuestionsRequest request,
                                                     @AuthenticationPrincipal Principal me) {
        boolean dryRun = request.dryRun() == null || request.dryRun();
        QuestionSource source;
        try {
            source = QuestionSource.of(request.source());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(e.getMessage());
        }
        int defaultScore = request.defaultScore() == null ? 5 : request.defaultScore();
        return ApiResponse.ok(questionImportService.importJsonl(me.userId(), request.jsonl(),
                source, defaultScore, request.license(), dryRun));
    }

    /**
     * 按配额自动组卷。只对草稿状态的考试有效，且只从当前老师自己的题库里抽。
     *
     * <p>返回实际抽到的明细（每条配额抽了几道）与写入条数 ——
     * 调用方要能看出"单选 10/10、判断 3/5"这种情况，
     * 而不是只拿到一个总数去猜哪条配额被截了。
     */
    @PostMapping("/exams/{examId}/auto-compose")
    public ApiResponse<AutoComposeService.Result> autoCompose(
            @PathVariable Long examId,
            @Valid @RequestBody AutoComposeRequest request,
            @AuthenticationPrincipal Principal me) {
        return ApiResponse.ok(autoComposeService.compose(examId, request.subject(), request.quotas(),
                request.score(), me.userId()));
    }

    /**
     * 改题。**整体覆盖**，不是部分更新。
     *
     * <p>为什么复用 {@link CreateQuestionRequest} 而不是另定一个 Update 请求体：
     * 前端本来就把整张表单提交上来，两套字段一旦漂移，就会出现"建题时能填的字段，
     * 改题时填不进去"这种只在某个入口才暴露的缺口。同一个 record 不可能漂。
     *
     * <p>为什么不是 PATCH 语义（只改传了的字段）：缺省值在这里是有含义的 ——
     * 不传 options 表示"这道题没有选项"（判断题），不传 score 表示"用默认分值"。
     * 把"没传"和"传了 null"混在一起判断，最后一定会有一条题被改成它本不该有的样子。
     */
    @PutMapping("/questions/{questionId}")
    public ApiResponse<Void> updateQuestion(@PathVariable Long questionId,
                                            @Valid @RequestBody CreateQuestionRequest request,
                                            @AuthenticationPrincipal Principal me) {
        Question q = new Question();
        q.setSubject(request.subject());
        q.setType(request.type());
        q.setStem(request.stem());
        q.setOptions(request.options());
        q.setAnswer(request.answer());
        q.setScore(request.score() == null ? 5 : request.score());
        q.setDifficulty(request.difficulty() == null ? 1 : request.difficulty());
        examService.updateQuestion(questionId, q, me.userId());
        return ApiResponse.ok(null);
    }

    /**
     * 删题。被任何试卷引用就拒绝 —— 删掉会让那几份卷子少一道题，
     * 历史答卷的作答也会指向一个不存在的题目。
     */
    @DeleteMapping("/questions/{questionId}")
    public ApiResponse<Void> deleteQuestion(@PathVariable Long questionId,
                                            @AuthenticationPrincipal Principal me) {
        examService.deleteQuestion(questionId, me.userId());
        return ApiResponse.ok(null);
    }

    /**
     * 从草稿试卷里移除一道题。
     *
     * <p>单独一个路径而不是复用 {@code /exams/{id}/questions} 加负分：
     * 删除是幂等的（删两次第二次报"不在这份卷子里"），而"加题"不是，
     * 两者混在一个入口里，前端要传一个空列表或负数来表达删除，
     * 这种约定没人记得住。
     */
    @DeleteMapping("/exams/{examId}/questions/{questionId}")
    public ApiResponse<Void> removeQuestion(@PathVariable Long examId,
                                            @PathVariable Long questionId,
                                            @AuthenticationPrincipal Principal me) {
        examService.removeQuestion(examId, questionId, me.userId());
        return ApiResponse.ok(null);
    }

    /**
     * 老师端考试列表（含草稿，只含自己建的）—— 前端需要它才能选一场考试去看统计 / 发布 / 结束。
     *
     * <p>分页返回 {@code {items,total,page,size}}。不传 page/size 时给 1/20：
     * 老师手上通常就几场考试，默认一页装得下；真要多了也不至于一次全拉回来。
     *
     * <p>page 从 1 开始（不是 0）。传 0 或负数按 1 处理 —— 与其 400 让前端去猜，
     * 不如夹到合法区间，翻页这种事不该成为一个错误来源。
     */
    @GetMapping("/exams")
    public ApiResponse<PageResult<Exam>> listExams(@RequestParam(defaultValue = "1") long page,
                                                   @RequestParam(defaultValue = "20") long size,
                                                   @AuthenticationPrincipal Principal me) {
        return ApiResponse.ok(examService.listExams(me.userId(), clampPage(page), clampSize(size)));
    }

    /** 题库列表（只含自己出的题）。subject / type 可选，不传就是全部。同样分页 */
    @GetMapping("/questions")
    public ApiResponse<PageResult<Question>> listQuestions(@RequestParam(required = false) String subject,
                                                           @RequestParam(required = false) String type,
                                                           @RequestParam(defaultValue = "1") long page,
                                                           @RequestParam(defaultValue = "20") long size,
                                                           @AuthenticationPrincipal Principal me) {
        return ApiResponse.ok(examService.listQuestions(subject, type, me.userId(),
                clampPage(page), clampSize(size)));
    }

    /** 页码下限 1。上限不设：总数摆在那，翻到 10 万页也只会返回空列表 */
    private static long clampPage(long page) {
        return page < 1 ? 1 : page;
    }

    /**
     * 每页条数夹在 1..100。
     *
     * <p>上限 100 和 {@code MybatisPlusConfig} 里 {@code setMaxLimit(100)} 是同一个数，
     * 这里显式夹一遍是为了让「前端传 size=100000」变成 100 而不是被插件静默改写 ——
     * 后者返回的 size 字段会和请求对不上，前端算页数就会错。
     */
    private static long clampSize(long size) {
        if (size < 1) {
            return 20;
        }
        return Math.min(size, 100);
    }

    @PostMapping("/exams")
    public ApiResponse<Long> createExam(@Valid @RequestBody CreateExamRequest request,
                                        @AuthenticationPrincipal Principal me) {
        int duration = request.durationMinutes() == null ? 60 : request.durationMinutes();
        int passScore = request.passScore() == null ? 60 : request.passScore();
        return ApiResponse.ok(examService.createExam(request.title(), request.subject(),
                request.startAt(), request.endAt(), duration, passScore, me.userId()));
    }

    /** 往卷子里加题。score 不传就用题目自带的分值 */
    @PostMapping("/exams/{examId}/questions")
    public ApiResponse<Integer> addQuestions(@PathVariable Long examId,
                                             @Valid @RequestBody AddQuestionsRequest request,
                                             @AuthenticationPrincipal Principal me) {
        return ApiResponse.ok(examService.addQuestions(examId, request.questionIds(), request.score(),
                me.userId()));
    }

    @PostMapping("/exams/{examId}/publish")
    public ApiResponse<Void> publish(@PathVariable Long examId,
                                     @AuthenticationPrincipal Principal me) {
        examService.publish(examId, me.userId());
        return ApiResponse.ok(null);
    }

    @PostMapping("/exams/{examId}/close")
    public ApiResponse<Void> close(@PathVariable Long examId,
                                   @AuthenticationPrincipal Principal me) {
        examService.close(examId, me.userId());
        return ApiResponse.ok(null);
    }

    @GetMapping("/exams/{examId}/stats")
    public ApiResponse<ExamScoreStats> stats(@PathVariable Long examId,
                                             @AuthenticationPrincipal Principal me) {
        return ApiResponse.ok(statsService.stats(examId, me.userId()));
    }

    @GetMapping("/exams/{examId}/distribution")
    public ApiResponse<List<ScoreBucket>> distribution(@PathVariable Long examId,
                                                       @AuthenticationPrincipal Principal me) {
        return ApiResponse.ok(statsService.distribution(examId, me.userId()));
    }

    /** 逐题正确率，正确率最低的排最前 —— 讲评先看这些题 */
    @GetMapping("/exams/{examId}/accuracy")
    public ApiResponse<List<QuestionAccuracy>> accuracy(@PathVariable Long examId,
                                                        @AuthenticationPrincipal Principal me) {
        return ApiResponse.ok(statsService.accuracy(examId, me.userId()));
    }

    @GetMapping("/exams/{examId}/progress")
    public ApiResponse<StatsService.Progress> progress(@PathVariable Long examId,
                                                       @AuthenticationPrincipal Principal me) {
        return ApiResponse.ok(statsService.progress(examId, me.userId()));
    }
}
