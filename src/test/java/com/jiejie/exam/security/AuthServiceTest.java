package com.jiejie.exam.security;

import com.jiejie.exam.domain.entity.ExamUser;
import com.jiejie.exam.domain.mapper.ExamUserMapper;
import com.jiejie.exam.web.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 建账号和登录。两件事都容易出安全问题，所以各钉一条：
 * 登录失败不能泄露"用户名是否存在"；建账号不能靠"先查后插"。
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private ExamUserMapper userMapper;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtUtil jwtUtil;

    @InjectMocks
    private AuthService authService;

    // ---------------- 登录 ----------------

    @Test
    void 用户名不存在和密码错误返回同一句话() {
        // 分开提示等于免费告诉攻击者哪些用户名是存在的
        when(userMapper.findActiveByUsername("nobody")).thenReturn(null);

        BusinessException e = assertThrows(BusinessException.class,
                () -> authService.login("nobody", "whatever"));

        assertEquals("用户名或密码错误", e.getMessage());
        assertEquals(401, e.code());
    }

    @Test
    void 密码不对时也不说哪错了() {
        ExamUser user = new ExamUser();
        user.setId(1L);
        user.setUsername("s001");
        user.setPasswordHash("$2a$10$hash");
        user.setRole("student");
        when(userMapper.findActiveByUsername("s001")).thenReturn(user);
        when(passwordEncoder.matches("wrong", "$2a$10$hash")).thenReturn(false);

        BusinessException e = assertThrows(BusinessException.class,
                () -> authService.login("s001", "wrong"));

        // 和"用户不存在"完全同一句，从响应上分不出来
        assertEquals("用户名或密码错误", e.getMessage());
    }

    // ---------------- 建账号 ----------------

    @Test
    void 角色只允许student和teacher() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> authService.createUser("x", "pw", "谁", "admin"));

        assertEquals("角色只能是 student 或 teacher", e.getMessage());
    }

    @Test
    void 用户名已存在时给友好提示() {
        ExamUser exist = new ExamUser();
        when(userMapper.findActiveByUsername("s001")).thenReturn(exist);

        BusinessException e = assertThrows(BusinessException.class,
                () -> authService.createUser("s001", "pw", "谁", "student"));

        assertEquals("用户名已存在: s001", e.getMessage());
    }

    @Test
    void 并发建同名用户时唯一键冲突也要给同一句提示() {
        // 先查后插之间有个窗口：两个请求都会查不到，然后一起 insert。
        // 真正裁决的是 uk_exam_user_username。没有这个 catch 的话，
        // 并发窗口会漏出一个 500，而串行路径给的是 400 + 友好提示 ——
        // 同一件事两种响应。
        when(userMapper.findActiveByUsername("s001")).thenReturn(null);
        when(passwordEncoder.encode("pw")).thenReturn("$2a$10$hash");
        when(userMapper.insert(any(ExamUser.class))).thenThrow(new DuplicateKeyException("uk_exam_user_username"));

        BusinessException e = assertThrows(BusinessException.class,
                () -> authService.createUser("s001", "pw", "谁", "student"));

        assertEquals("用户名已存在: s001", e.getMessage());
    }

    @Test
    void 正常建账号返回新id() {
        when(userMapper.findActiveByUsername("s009")).thenReturn(null);
        when(passwordEncoder.encode("pw")).thenReturn("$2a$10$hash");
        when(userMapper.insert(any(ExamUser.class))).thenAnswer(inv -> {
            ExamUser u = inv.getArgument(0);
            u.setId(42L);
            return 1;
        });

        assertEquals(42L, authService.createUser("s009", "pw", "新同学", "student"));
    }
}
