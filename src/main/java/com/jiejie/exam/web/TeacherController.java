package com.jiejie.exam.web;

import com.jiejie.exam.domain.dto.ExamScoreStats;
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

    /** 老师端考试列表（含草稿）—— 前端需要它才能选一场考试去看统计 / 发布 / 结束 */
    @GetMapping("/exams")
    public ApiResponse<List<Exam>> listExams() {
        return ApiResponse.ok(examService.listExams());
    }

    /** 题库列表。subject / type 可选，不传就是全部 */
    @GetMapping("/questions")
    public ApiResponse<List<Question>> listQuestions(@RequestParam(required = false) String subject,
                                                     @RequestParam(required = false) String type) {
        return ApiResponse.ok(examService.listQuestions(subject, type));
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
                                             @Valid @RequestBody AddQuestionsRequest request) {
        return ApiResponse.ok(examService.addQuestions(examId, request.questionIds(), request.score()));
    }

    @PostMapping("/exams/{examId}/publish")
    public ApiResponse<Void> publish(@PathVariable Long examId) {
        examService.publish(examId);
        return ApiResponse.ok(null);
    }

    @PostMapping("/exams/{examId}/close")
    public ApiResponse<Void> close(@PathVariable Long examId) {
        examService.close(examId);
        return ApiResponse.ok(null);
    }

    @GetMapping("/exams/{examId}/stats")
    public ApiResponse<ExamScoreStats> stats(@PathVariable Long examId) {
        return ApiResponse.ok(statsService.stats(examId));
    }

    @GetMapping("/exams/{examId}/distribution")
    public ApiResponse<List<ScoreBucket>> distribution(@PathVariable Long examId) {
        return ApiResponse.ok(statsService.distribution(examId));
    }

    /** 逐题正确率，正确率最低的排最前 —— 讲评先看这些题 */
    @GetMapping("/exams/{examId}/accuracy")
    public ApiResponse<List<QuestionAccuracy>> accuracy(@PathVariable Long examId) {
        return ApiResponse.ok(statsService.accuracy(examId));
    }

    @GetMapping("/exams/{examId}/progress")
    public ApiResponse<StatsService.Progress> progress(@PathVariable Long examId) {
        return ApiResponse.ok(statsService.progress(examId));
    }
}
