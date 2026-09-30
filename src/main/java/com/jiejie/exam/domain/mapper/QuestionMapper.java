package com.jiejie.exam.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jiejie.exam.domain.entity.Question;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface QuestionMapper extends BaseMapper<Question> {

    /**
     * 按"科目 + 题型"抽题 —— 手工组卷时的候选池。
     * 走 idx_question_subject_type(subject, type)。
     */
    @Select("SELECT id, subject, type, stem, options, answer, score, difficulty, created_by, created_at " +
            "FROM question WHERE subject = #{subject} AND type = #{type} ORDER BY id")
    List<Question> findBySubjectAndType(@Param("subject") String subject, @Param("type") String type);

    /**
     * 按 id 批量取题（含正确答案）—— 只给判分等服务端逻辑用，
     * 任何返回给浏览器的路径都必须走 PaperItem，不能返回 Question。
     */
    @Select("<script>" +
            "SELECT id, subject, type, stem, options, answer, score, difficulty, created_by, created_at " +
            "FROM question WHERE id IN " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>" +
            "</script>")
    List<Question> findByIds(@Param("ids") List<Long> ids);
}
