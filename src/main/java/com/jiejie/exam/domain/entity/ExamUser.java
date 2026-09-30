package com.jiejie.exam.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("exam_user")
public class ExamUser {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    /** SHA-256，明文不落库 */
    private String passwordHash;
    private String realName;
    /** student | teacher */
    private String role;
    private String status;
    private LocalDateTime createdAt;
}
