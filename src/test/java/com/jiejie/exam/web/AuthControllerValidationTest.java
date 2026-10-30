package com.jiejie.exam.web;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestBody;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 建账号接口的参数校验。
 *
 * <p>这是一个真实踩过的坑：{@code CreateUserRequest} 上四个 {@code @NotBlank}
 * 写着，但 Controller 的形参上<b>没有 {@code @Valid}</b> —— 于是校验注解
 * 一条都没执行，实测能建出空口令账号、还能用空口令登录成功，
 * 纯空格的用户名也能入库。
 *
 * <p>光测「注解写在 record 上」没有意义：那部分本来就是对的。
 * 要钉的是「Controller 真的触发了校验」，所以两条断言缺一不可：
 * <ol>
 *   <li>{@code @Valid} 确实挂在 {@code createUser} 的请求体形参上
 *       —— 谁把它删掉，这里立刻红；</li>
 *   <li>空口令/空用户名/空姓名的请求体，确实会被校验器判为不合法
 *       —— 证明那些约束本身是有效的，不是写了个不生效的注解。</li>
 * </ol>
 */
class AuthControllerValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        if (factory != null) {
            factory.close();
        }
    }

    @Test
    void createUser的请求体参数上有Valid() throws NoSuchMethodException {
        Method m = AuthController.class.getMethod("createUser",
                AuthController.CreateUserRequest.class,
                com.jiejie.exam.security.Principal.class);
        Parameter body = null;
        for (Parameter p : m.getParameters()) {
            if (p.isAnnotationPresent(RequestBody.class)) {
                body = p;
                break;
            }
        }
        assertTrue(body != null, "createUser 应该有一个 @RequestBody 参数");

        boolean hasValid = false;
        for (Annotation a : body.getAnnotations()) {
            if (a.annotationType().getName().equals("jakarta.validation.Valid")) {
                hasValid = true;
                break;
            }
        }
        // 少了它，record 上那四个 @NotBlank 就是一段死代码：
        // 空口令账号能建出来，还能用空口令登录。
        assertTrue(hasValid, "createUser 的 @RequestBody 参数必须带 @Valid，否则 @NotBlank 全部不生效");
    }

    @Test
    void 空口令的建账号请求被判为不合法() {
        Set<ConstraintViolation<AuthController.CreateUserRequest>> v = validator.validate(
                new AuthController.CreateUserRequest("s010", "", "空口令", "student"));

        assertFalse(v.isEmpty(), "空口令必须被校验挡住");
        assertEquals("password", v.iterator().next().getPropertyPath().toString());
    }

    @Test
    void 纯空格用户名被判为不合法() {
        // @NotBlank 和 @NotEmpty 的区别就在这里：空格串不是空串，
        // 用 @NotEmpty 会一路放行，库里留下一个肉眼看不见的用户名。
        Set<ConstraintViolation<AuthController.CreateUserRequest>> v = validator.validate(
                new AuthController.CreateUserRequest("   ", "pw123456", "空格名", "student"));

        assertFalse(v.isEmpty(), "纯空格用户名必须被校验挡住");
        assertEquals("username", v.iterator().next().getPropertyPath().toString());
    }

    @Test
    void 空姓名被判为不合法() {
        Set<ConstraintViolation<AuthController.CreateUserRequest>> v = validator.validate(
                new AuthController.CreateUserRequest("s011", "pw123456", "", "student"));

        assertFalse(v.isEmpty());
        assertEquals("realName", v.iterator().next().getPropertyPath().toString());
    }

    @Test
    void 合法的建账号请求通过校验() {
        // 反向断言：上面三条挡的是坏输入，不能顺手把好输入也挡了。
        Set<ConstraintViolation<AuthController.CreateUserRequest>> v = validator.validate(
                new AuthController.CreateUserRequest("s012", "pw123456", "新同学", "student"));

        assertEquals(Set.of(), v.stream()
                .map(x -> x.getPropertyPath().toString())
                .collect(Collectors.toSet()));
    }
}
