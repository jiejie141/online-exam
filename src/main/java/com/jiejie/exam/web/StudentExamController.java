package com.jiejie.exam.web;

import com.jiejie.exam.domain.dto.PaperItem;
import com.jiejie.exam.domain.entity.Exam;
import com.jiejie.exam.domain.mapper.ExamMapper;
import com.jiejie.exam.exam.ExamSessionService;
import com.jiejie.exam.security.Principal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/** 学生端：看能考什么、进入考试、存答案、交卷、查分。 */
@RestController
@RequestMapping("/api")
public class StudentExamController {

    public record SaveAnswerRequest(@NotNull Long questionId, String answer) {
    }

    private final ExamSessionService sessionService;
    private final ExamMapper examMapper;
    private final Clock clock;

    public StudentExamController(ExamSessionService sessionService, ExamMapper examMapper, Clock clock) {
        this.sessionService = sessionService;
        this.examMapper = examMapper;
        this.clock = clock;
    }

    /** 当前可参加的考试（时间窗已开且未结束） */
    @GetMapping("/exams/available")
    public ApiResponse<List<Exam>> available() {
        return ApiResponse.ok(examMapper.findOngoingExams(LocalDateTime.now(clock)));
    }

    /**
     * 进入考试。已经进入过就直接返回那份答卷 ——
     * 刷新页面、断线重连都走这条路径，不会重开一份把之前的作答丢掉。
     */
    @PostMapping("/exams/{examId}/start")
    public ApiResponse<ExamSessionService.StartResult> start(@PathVariable Long examId,
                                                             @AuthenticationPrincipal Principal me) {
        return ApiResponse.ok(sessionService.start(examId, me.userId()));
    }

    /** 取卷面。不含正确答案 —— 见 PaperItem 的说明 */
    @GetMapping("/exams/{examId}/paper")
    public ApiResponse<List<PaperItem>> paper(@PathVariable Long examId) {
        return ApiResponse.ok(sessionService.paper(examId));
    }

    /** 保存一道题的作答。前端每答一题就调一次，重复调用是覆盖而不是新增 */
    @PostMapping("/records/{recordId}/answers")
    public ApiResponse<Void> saveAnswer(@PathVariable Long recordId,
                                        @Valid @RequestBody SaveAnswerRequest request,
                                        @AuthenticationPrincipal Principal me) {
        sessionService.saveAnswer(recordId, me.userId(), request.questionId(), request.answer());
        return ApiResponse.ok(null);
    }

    /** 交卷。重复点击是安全的：只有第一次会真正改状态并触发判分 */
    @PostMapping("/records/{recordId}/submit")
    public ApiResponse<ExamSessionService.SubmitResult> submit(@PathVariable Long recordId,
                                                               @AuthenticationPrincipal Principal me) {
        return ApiResponse.ok(sessionService.submit(recordId, me.userId()));
    }

    /** 查成绩。判分还没完成时返回 400 提示稍后再看，而不是返回一个 0 分 */
    @GetMapping("/records/{recordId}/result")
    public ApiResponse<ExamSessionService.ExamResultView> result(@PathVariable Long recordId,
                                                                 @AuthenticationPrincipal Principal me) {
        return ApiResponse.ok(sessionService.result(recordId, me.userId()));
    }
}
