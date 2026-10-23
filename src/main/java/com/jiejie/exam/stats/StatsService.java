package com.jiejie.exam.stats;

import com.jiejie.exam.domain.dto.ExamScoreStats;
import com.jiejie.exam.domain.dto.QuestionAccuracy;
import com.jiejie.exam.domain.dto.ScoreBucket;
import com.jiejie.exam.domain.mapper.ExamAnswerMapper;
import com.jiejie.exam.domain.mapper.ExamRecordMapper;
import com.jiejie.exam.exam.ExamAccess;
import com.jiejie.exam.exam.RecordStatus;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 老师端看成绩：概览、分布、逐题正确率、交卷进度。
 *
 * <p>四个入口都要先过 {@link ExamAccess#requireOwned}。成绩统计比别的接口更敏感 ——
 * 它把一场考试所有人的分数、每题正确率都摊开，而这本来只该给出题的那位老师看。
 * 原来这里唯一的校验是「考试存在」，也就是任何老师只要知道 examId
 * 就能看到别人那场考试的全班成绩。
 */
@Service
public class StatsService {

    public record Progress(long submitted, long ongoing) {
    }

    private final ExamRecordMapper recordMapper;
    private final ExamAnswerMapper answerMapper;
    private final ExamAccess examAccess;

    public StatsService(ExamRecordMapper recordMapper, ExamAnswerMapper answerMapper, ExamAccess examAccess) {
        this.recordMapper = recordMapper;
        this.answerMapper = answerMapper;
        this.examAccess = examAccess;
    }

    public ExamScoreStats stats(Long examId, Long teacherId) {
        examAccess.requireOwned(examId, teacherId);
        ExamScoreStats s = recordMapper.stats(examId);
        // 没人判分时这条 SQL 仍然返回一行（COUNT 是 0，而 AVG/MAX/MIN 是 NULL），
        // 这里把 total/passCount 补成明确的 0，让前端能直接显示「0 人参加」。
        //
        // 注意**不**给 avgScore/maxScore/minScore 填 0：没人交卷时「平均分 0」
        // 是假数据，会显示成一个真实存在的 0 分。它们保持 null，
        // 前端应该按 total == 0 判断「还没有成绩」，而不是看 avgScore 是不是 null。
        if (s == null || s.getTotal() == null || s.getTotal() == 0) {
            ExamScoreStats empty = new ExamScoreStats();
            empty.setTotal(0);
            empty.setPassCount(0);
            return empty;
        }
        return s;
    }

    public List<ScoreBucket> distribution(Long examId, Long teacherId) {
        examAccess.requireOwned(examId, teacherId);
        return recordMapper.distribution(examId);
    }

    public List<QuestionAccuracy> accuracy(Long examId, Long teacherId) {
        examAccess.requireOwned(examId, teacherId);
        return answerMapper.accuracyByExam(examId);
    }

    public Progress progress(Long examId, Long teacherId) {
        examAccess.requireOwned(examId, teacherId);
        // submitted 和 graded 都算「已交」，判分只是时间问题
        long submitted = recordMapper.countByExamAndStatus(examId, RecordStatus.SUBMITTED.code())
                + recordMapper.countByExamAndStatus(examId, RecordStatus.GRADED.code());
        long ongoing = recordMapper.countByExamAndStatus(examId, RecordStatus.ONGOING.code());
        return new Progress(submitted, ongoing);
    }
}
