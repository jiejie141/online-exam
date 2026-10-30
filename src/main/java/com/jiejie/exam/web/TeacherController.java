package com.jiejie.exam.web;

import com.jiejie.exam.domain.dto.ExamScoreStats;
import com.jiejie.exam.domain.dto.PageResult;
import com.jiejie.exam.domain.dto.QuestionAccuracy;
import com.jiejie.exam.domain.dto.ScoreBucket;
import com.jiejie.exam.domain.entity.Exam;
import com.jiejie.exam.domain.entity.Question;
import com.jiejie.exam.exam.ExamService;
import com.jiejie.exam.security.Principal;
import com.jiejie.exam.stats.StatsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

    private final ExamService examService;
    private final StatsService statsService;

    public TeacherController(ExamService examService, StatsService statsService) {
        this.examService = examService;
        this.statsService = statsService;
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
