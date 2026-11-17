package com.yiqi.aitoolbench.auth;

import com.yiqi.aitoolbench.user.UserRepository;
import com.yiqi.aitoolbench.user.UserRepository.SysUser;
import com.yiqi.aitoolbench.security.DataScope;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    private final UserRepository userRepository;
    private final SessionRepository sessionRepository;
    private final PasswordService passwordService;

    public AuthService(UserRepository userRepository, SessionRepository sessionRepository, PasswordService passwordService) {
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
        this.passwordService = passwordService;
    }

    public Map<String, Object> login(String account, String password, String ipAddress, String userAgent) {
        var user = userRepository.findByAccount(account)
                .orElseThrow(() -> new IllegalArgumentException("账号或密码错误"));
        if (!user.enabled()) {
            throw new IllegalArgumentException("账号已禁用");
        }
        if (user.lockedUntil() != null && Instant.now().isBefore(user.lockedUntil())) {
            throw new IllegalArgumentException("登录失败次数过多，请稍后再试");
        }
        if (!passwordService.matches(password, user.passwordHash())) {
            userRepository.recordLoginFailure(user.id(), user.failedAttempts() + 1);
            throw new IllegalArgumentException("账号或密码错误");
        }
        if (passwordService.needsRehash(user.passwordHash())) {
            userRepository.updatePassword(user.id(), passwordService.hash(password), user.mustChangePassword());
        }
        userRepository.clearLoginFailure(user.id());
        var refreshed = userRepository.findById(user.id()).orElse(user);
        var token = sessionRepository.create(refreshed.id(), refreshed.sessionVersion(), ipAddress, userAgent);
        return Map.of(
                "accessToken", token,
                "mustChangePassword", refreshed.mustChangePassword(),
                "user", userView(refreshed),
                "permissions", userRepository.listPermissionCodes(refreshed.id()));
    }

    public AuthContext requireContext(String token) {
        var session = sessionRepository.findActive(token)
                .orElseThrow(() -> new IllegalArgumentException("登录态无效或已过期"));
        var user = userRepository.findById(session.userId())
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        if (!user.enabled()) {
            sessionRepository.revoke(token);
            throw new IllegalArgumentException("账号已禁用");
        }
        if (session.userSessionVersion() != user.sessionVersion()) {
            sessionRepository.revoke(token);
            throw new IllegalArgumentException("登录态已失效");
        }
        sessionRepository.touch(token);
        return new AuthContext(token, user.id(), user.account(), user.displayName(), user.mustChangePassword(),
                user.sessionVersion(), userRepository.listPermissionCodes(user.id()));
    }

    public Map<String, Object> currentUser(String token) {
        var context = requireContext(token);
        return Map.of(
                "id", context.userId(),
                "account", context.account(),
                "name", context.displayName(),
                "displayName", context.displayName(),
                "mustChangePassword", context.mustChangePassword(),
                "departmentKeys", userRepository.listDepartmentKeys(context.userId()),
                "dataScope", effectiveDataScope(context.userId()).name(),
                "permissions", context.permissions(),
                "superAdmin", userRepository.listActiveUserRoleCodes(context.userId()).contains("admin") && userRepository.listRoles().stream().anyMatch(r -> "admin".equals(r.code()) && r.builtIn() && r.enabled()));
    }

    public Map<String, Object> changePassword(String token, String oldPassword, String newPassword) {
        var context = requireContext(token);
        var user = userRepository.findById(context.userId())
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        if (!passwordService.matches(oldPassword, user.passwordHash())) {
            throw new IllegalArgumentException("原密码错误");
        }
        passwordService.validateNewPassword(user.account(), oldPassword, newPassword);
        userRepository.updatePassword(user.id(), passwordService.hash(newPassword), false);
        return Map.of("changed", true);
    }

    public void logout(String token) {
        sessionRepository.revoke(token);
    }

    public Map<String, Object> createUser(String account, String displayName, String roleCode) {
        return createUser(account, displayName, roleCode, null);
    }

    public Map<String, Object> createUser(String account, String displayName, String roleCode, String initialPassword) {
        var temporaryPassword = initialPassword == null || initialPassword.isBlank()
                ? passwordService.generateTemporaryPassword() : initialPassword;
        if (initialPassword != null && !initialPassword.isBlank()
                && !initialPassword.equals(account + "123456")) {
            passwordService.validateNewPassword(account, "", initialPassword);
        }
        var id = userRepository.createUser(account, displayName, passwordService.hash(temporaryPassword), true);
        if (roleCode != null && !roleCode.isBlank()) {
            userRepository.addUserRole(id, roleCode);
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("id", id);
        result.put("account", account);
        result.put("name", displayName);
        result.put("displayName", displayName);
        result.put("mustChangePassword", true);
        result.put("temporaryPassword", temporaryPassword);
        return result;
    }

    public Map<String, Object> resetPassword(String userId) {
        var user = userRepository.findById(userId).orElseThrow(() -> new IllegalArgumentException("用户不存在: " + userId));
        var temporaryPassword = passwordService.generateTemporaryPassword();
        userRepository.updatePassword(user.id(), passwordService.hash(temporaryPassword), true);
        userRepository.clearLoginFailure(user.id());
        userRepository.bumpSessionVersion(user.id());
        sessionRepository.revokeByUserId(user.id());
        return Map.of("reset", true, "temporaryPassword", temporaryPassword);
    }

    public Map<String, Object> updateUserStatus(String userId, boolean enabled) {
        userRepository.updateEnabled(userId, enabled);
        userRepository.bumpSessionVersion(userId);
        if (!enabled) {
            sessionRepository.revokeByUserId(userId);
        }
        return Map.of("id", userId, "enabled", enabled);
    }

    private Map<String, Object> userView(SysUser user) {
        return Map.of(
                "id", user.id(),
                "account", user.account(),
                "name", user.displayName(),
                "displayName", user.displayName(),
                "enabled", user.enabled(),
                "departmentKeys", userRepository.listDepartmentKeys(user.id()),
                "dataScope", effectiveDataScope(user.id()).name(),
                "mustChangePassword", user.mustChangePassword(),
                "superAdmin", userRepository.listActiveUserRoleCodes(user.id()).contains("admin") && userRepository.listRoles().stream().anyMatch(r -> "admin".equals(r.code()) && r.builtIn() && r.enabled()));
    }

    private DataScope effectiveDataScope(String userId) {
        return userRepository.listDataScopes(userId).stream()
                .map(DataScope::from)
                .max(Comparator.comparingInt(DataScope::priority))
                .orElse(DataScope.SELF);
    }
}
