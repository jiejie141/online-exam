package com.jiejie.exam.security;

/** 当前登录用户。JWT 里带的就是这四个字段，服务端不查库也知道是谁。 */
public record Principal(Long userId, String username, String role, String realName) {

    public boolean isTeacher() {
        return "teacher".equals(role);
    }
}
