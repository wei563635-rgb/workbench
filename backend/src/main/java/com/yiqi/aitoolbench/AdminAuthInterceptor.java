package com.yiqi.aitoolbench;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AdminAuthInterceptor implements HandlerInterceptor {
    private final SessionStore sessionStore;

    public AdminAuthInterceptor(SessionStore sessionStore) {
        this.sessionStore = sessionStore;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        var authorization = request.getHeader("Authorization");
        var token = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring("Bearer ".length())
                : null;
        if (!sessionStore.valid(token)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"后台接口需要登录后访问\"}");
            return false;
        }
        if (sessionStore.mustChangePassword(token) && !isPasswordChangeAllowed(request)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":\"PASSWORD_CHANGE_REQUIRED\",\"message\":\"请先修改初始密码\"}");
            return false;
        }
        return true;
    }

    private boolean isPasswordChangeAllowed(HttpServletRequest request) {
        var path = request.getRequestURI();
        var method = request.getMethod();
        return ("POST".equals(method) && "/api/auth/change-password".equals(path))
                || ("POST".equals(method) && "/api/auth/logout".equals(path))
                || ("GET".equals(method) && "/api/auth/me".equals(path))
                || ("POST".equals(method) && "/api/legacy/auth/change-password".equals(path))
                || ("GET".equals(method) && "/api/legacy/auth/me".equals(path));
    }
}
