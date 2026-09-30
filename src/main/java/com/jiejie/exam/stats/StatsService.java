package com.jiejie.exam.stats;

import com.jiejie.exam.domain.dto.ExamScoreStats;
import com.jiejie.exam.domain.dto.QuestionAccuracy;
import com.jiejie.exam.domain.dto.ScoreBucket;
import com.jiejie.exam.domain.entity.Exam;
import com.jiejie.exam.domain.mapper.ExamAnswerMapper;
import com.jiejie.exam.domain.mapper.ExamMapper;
import com.jiejie.exam.domain.mapper.ExamRecordMapper;
import com.jiejie.exam.exam.RecordStatus;
import com.jiejie.exam.web.BusinessException;
import org.springframework.stereotype.Service;

import java.util.List;

/** 老师端看成绩：概览、分布、逐题正确率、交卷进度。 */
@Service
public class StatsService {

    public record Progress(long submitted, long ongoing) {
    }

    private final ExamMapper examMapper;
    private final ExamRecordMapper recordMapper;
    private final ExamAnswerMapper answerMapper;

    public StatsService(ExamMapper examMapper, ExamRecordMapper recordMapper, ExamAnswerMapper answerMapper) {
        this.examMapper = examMapper;
        this.recordMapper = recordMapper;
        this.answerMapper = answerMapper;
    }

    public ExamScoreStats stats(Long examId) {
        requireExam(examId);
        ExamScoreStats s = recordMapper.stats(examId);
        // 没人交卷时 SQL 返回的是一行全 0（MAX/MIN 是 NULL），这里补成"零人"的结构，
        // 免得前端拿到 avgScore=null 之后还要自己判空
        if (s == null || s.getTotal() == null || s.getTotal() == 0) {
            ExamScoreStats empty = new ExamScoreStats();
            empty.setTotal(0);
            empty.setPassCount(0);
            return empty;
        }
        return s;
    }

    public List<ScoreBucket> distribution(Long examId) {
        requireExam(examId);
        return recordMapper.distribution(examId);
    }

    public List<QuestionAccuracy> accuracy(Long examId) {
        requireExam(examId);
        return answerMapper.accuracyByExam(examId);
    }

    public Progress progress(Long examId) {
        requireExam(examId);
        // submitted 和 graded 都算"已交"，判分只是时间问题
        long submitted = recordMapper.countByExamAndStatus(examId, RecordStatus.SUBMITTED.code())
                + recordMapper.countByExamAndStatus(examId, RecordStatus.GRADED.code());
        long ongoing = recordMapper.countByExamAndStatus(examId, RecordStatus.ONGOING.code());
        return new Progress(submitted, ongoing);
    }

    private void requireExam(Long examId) {
        Exam exam = examMapper.selectById(examId);
        if (exam == null) {
            throw new BusinessException("考试不存在: " + examId);
        }
    }
}
