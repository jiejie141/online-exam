package com.jiejie.exam.web;

import com.jiejie.exam.security.AuthService;
import com.jiejie.exam.security.Principal;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class AuthController {

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record LoginResponse(String token, Long userId, String role, String realName) {
    }

    public record CreateUserRequest(@NotBlank String username, @NotBlank String password,
                                    @NotBlank String realName, @NotBlank String role) {
    }

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/auth/login")
    public ApiResponse<LoginResponse> login(@RequestBody LoginRequest request) {
        AuthService.LoginResult result = authService.login(request.username(), request.password());
        Principal p = result.principal();
        return ApiResponse.ok(new LoginResponse(result.token(), p.userId(), p.role(), p.realName()));
    }

    /** 建账号。路径在 /api/teacher/ 下，由 Spring Security 的 ROLE_teacher 拦住 */
    @PostMapping("/teacher/users")
    public ApiResponse<Long> createUser(@RequestBody CreateUserRequest request,
                                        @AuthenticationPrincipal Principal me) {
        if (me == null || !me.isTeacher()) {
            throw new BusinessException(403, "只有老师可以建账号");
        }
        return ApiResponse.ok(authService.createUser(request.username(), request.password(),
                request.realName(), request.role()));
    }
}
