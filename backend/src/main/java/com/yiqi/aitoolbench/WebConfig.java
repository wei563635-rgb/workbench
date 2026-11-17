package com.yiqi.aitoolbench;

import com.yiqi.aitoolbench.security.PermissionInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final PermissionInterceptor permissionInterceptor;

    public WebConfig(PermissionInterceptor permissionInterceptor) {
        this.permissionInterceptor = permissionInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(permissionInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/login", "/api/legacy/auth/login", "/api/public/**",
                        "/api/files/demo-download/**", "/api/tool-sessions/exchange", "/api/tool-sessions/introspect",
                        "/api/tool-sessions/detail-page-context", "/api/tool-sessions/detail-page-directory",
                        "/api/tool-sessions/new-chat-exchange", "/api/tool-sessions/new-chat-context");
    }
}
