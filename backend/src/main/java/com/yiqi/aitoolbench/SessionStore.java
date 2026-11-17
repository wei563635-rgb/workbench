package com.yiqi.aitoolbench;

import com.yiqi.aitoolbench.auth.AuthService;
import com.yiqi.aitoolbench.auth.SessionRepository;
import org.springframework.stereotype.Component;

@Component
public class SessionStore {
    private final SessionRepository sessionRepository;
    private final AuthService authService;

    public SessionStore(SessionRepository sessionRepository, AuthService authService) {
        this.sessionRepository = sessionRepository;
        this.authService = authService;
    }

    public String create(String userId) {
        return sessionRepository.create(userId, 1, null, null);
    }

    public boolean valid(String token) {
        try {
            authService.requireContext(token);
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    public String userId(String token) {
        try {
            return authService.requireContext(token).userId();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    public boolean mustChangePassword(String token) {
        try {
            return authService.requireContext(token).mustChangePassword();
        } catch (RuntimeException ex) {
            return false;
        }
    }
}
