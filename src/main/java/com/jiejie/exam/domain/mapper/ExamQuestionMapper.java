package com.jiejie.exam.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jiejie.exam.domain.dto.PaperItem;
import com.jiejie.exam.domain.entity.ExamQuestion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ExamQuestionMapper extends BaseMapper<ExamQuestion> {

    /**
     * 取卷面。走 idx_exam_question_seq(exam_id, seq) 直接按序返回，不需要额外排序。
     *
     * <p><b>SELECT 里没有 q.answer。</b>正确答案不参与这条查询，
     * 即使有人以后往 PaperItem 里加字段，也拿不到答案 —— 漏答案是查不出来而不是靠自觉。
     */
    @Select("SELECT q.id, q.type, q.stem, q.options, eq.score, eq.seq " +
            "FROM exam_question eq JOIN question q ON q.id = eq.question_id " +
            "WHERE eq.exam_id = #{examId} ORDER BY eq.seq, eq.id")
    List<PaperItem> selectPaper(@Param("examId") Long examId);

    /**
     * 判分时用的"卷面题号 → 分值"映射。服务端内部用，所以这里要带 answer。
     * 返回 ExamQuestion + 题目答案的组合，用 Map 承接：
     * 注意 MyBatis <b>不会</b>对 Map 结果做下划线转驼峰，所以别名必须写成 snake_case。
     */
    @Select("SELECT eq.question_id, eq.score, q.type, q.answer " +
            "FROM exam_question eq JOIN question q ON q.id = eq.question_id " +
            "WHERE eq.exam_id = #{examId}")
    List<java.util.Map<String, Object>> selectAnswerKey(@Param("examId") Long examId);
}
