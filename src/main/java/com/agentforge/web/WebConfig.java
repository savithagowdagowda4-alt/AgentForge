package com.agentforge.web;

import com.agentforge.auth.CurrentUser;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final CurrentUser currentUser;

    public WebConfig(CurrentUser currentUser) {
        this.currentUser = currentUser;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(currentUser)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/**", "/api/status");
    }
}
