package com.jiejie.exam.security;

import com.jiejie.exam.domain.entity.ExamUser;
import com.jiejie.exam.domain.mapper.ExamUserMapper;
import com.jiejie.exam.web.BusinessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final ExamUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public AuthService(ExamUserMapper userMapper, PasswordEncoder passwordEncoder, JwtUtil jwtUtil) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
    }

    /** 登录结果。把身份一起返回，免得调用方再解一遍令牌 */
    public record LoginResult(String token, Principal principal) {
    }

    /**
     * 登录。用户名不存在和密码错误<b>返回同一句话</b> ——
     * 分开提示等于免费告诉攻击者哪些用户名是存在的。
     */
    public LoginResult login(String username, String password) {
        ExamUser user = userMapper.findActiveByUsername(username);
        if (user == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BusinessException(401, "用户名或密码错误");
        }
        Principal principal = new Principal(user.getId(), user.getUsername(), user.getRole(), user.getRealName());
        return new LoginResult(jwtUtil.create(principal), principal);
    }

    /** 建账号。只给老师端调用（Controller 层用 Spring Security 的 ROLE_teacher 拦住） */
    public Long createUser(String username, String rawPassword, String realName, String role) {
        if (!"student".equals(role) && !"teacher".equals(role)) {
            throw new BusinessException("角色只能是 student 或 teacher");
        }
        // 先查一次是为了给出友好提示（大多数情况够了），但**不能只靠它**：
        // 两个请求同时建同名用户时都会查不到，然后一起走到 insert。
        // 真正裁决的是 uk_exam_user_username —— 捕获它转成同一句话，
        // 而不是让并发窗口漏出一个 500。
        ExamUser exist = userMapper.findActiveByUsername(username);
        if (exist != null) {
            throw new BusinessException("用户名已存在: " + username);
        }
        ExamUser user = new ExamUser();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setRealName(realName);
        user.setRole(role);
        user.setStatus("active");
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("用户名已存在: " + username);
        }
        return user.getId();
    }
}
