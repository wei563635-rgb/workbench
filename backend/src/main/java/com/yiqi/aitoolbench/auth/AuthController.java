package com.yiqi.aitoolbench.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/api/auth/login")
    public Map<String, Object> login(@RequestBody LoginRequest request, HttpServletRequest servletRequest) {
        return authService.login(request.account(), request.password(), servletRequest.getRemoteAddr(), servletRequest.getHeader("User-Agent"));
    }

    @GetMapping("/api/auth/me")
    public Map<String, Object> me(@RequestHeader("Authorization") String authorization) {
        return authService.currentUser(tokenFromAuthorization(authorization));
    }

    @PostMapping("/api/auth/change-password")
    public Map<String, Object> changePassword(@RequestHeader("Authorization") String authorization, @RequestBody ChangePasswordRequest request) {
        return authService.changePassword(tokenFromAuthorization(authorization), request.oldPassword(), request.newPassword());
    }

    @PostMapping("/api/auth/logout")
    public Map<String, Object> logout(@RequestHeader("Authorization") String authorization) {
        authService.logout(tokenFromAuthorization(authorization));
        return Map.of("loggedOut", true);
    }

    private String tokenFromAuthorization(String authorization) {
        return authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring("Bearer ".length())
                : null;
    }

    public record LoginRequest(String account, String password) {}
    public record ChangePasswordRequest(String oldPassword, String newPassword) {}
}
