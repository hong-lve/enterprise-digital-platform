package com.company.dataops.dataservice.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {
    private final OperationAuditInterceptor operationAuditInterceptor;

    public WebMvcConfig(OperationAuditInterceptor operationAuditInterceptor) {
        this.operationAuditInterceptor = operationAuditInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(operationAuditInterceptor)
            .addPathPatterns("/data-service-admin/**");
    }
}
