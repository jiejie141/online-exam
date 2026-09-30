package com.jiejie.exam.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jiejie.exam.domain.entity.ExamUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ExamUserMapper extends BaseMapper<ExamUser> {

    /** 登录时用。username 上有唯一索引，这条查询走索引且最多返回一行 */
    @Select("SELECT id, username, password_hash, real_name, role, status, created_at " +
            "FROM exam_user WHERE username = #{username} AND status = 'active'")
    ExamUser findActiveByUsername(@Param("username") String username);
}
