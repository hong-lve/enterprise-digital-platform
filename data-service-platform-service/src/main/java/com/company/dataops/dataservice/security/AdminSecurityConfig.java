package com.company.dataops.dataservice.security;

import com.company.dataops.dataservice.common.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

@Configuration
public class AdminSecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    FilterRegistrationBean<AdminAuthenticationFilter> adminAuthenticationFilterRegistration(
        AdminAuthenticationFilter filter
    ) {
        FilterRegistrationBean<AdminAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    SecurityFilterChain securityFilterChain(
        HttpSecurity http,
        AdminAuthenticationFilter adminAuthenticationFilter,
        ObjectMapper objectMapper
    ) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(Customizer.withDefaults())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .formLogin(form -> form.disable())
            .httpBasic(basic -> basic.disable())
            .logout(logout -> logout.disable())
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/data-service-admin/auth/login").permitAll()
                .requestMatchers(
                    "/openapi/**",
                    "/actuator/health/**",
                    "/actuator/prometheus",
                    "/error"
                ).permitAll()
                .requestMatchers(HttpMethod.GET, "/data-service-admin/data-sources/**")
                    .hasAuthority("DATASOURCE_READ")
                .requestMatchers("/data-service-admin/data-sources/**")
                    .hasAuthority("DATASOURCE_MANAGE")
                .requestMatchers(HttpMethod.GET, "/data-service-admin/datasets/**")
                    .hasAuthority("DATASET_READ")
                .requestMatchers("/data-service-admin/datasets/**")
                    .hasAuthority("DATASET_MANAGE")
                .requestMatchers(HttpMethod.GET, "/data-service-admin/apis/**")
                    .hasAuthority("API_READ")
                .requestMatchers(HttpMethod.POST, "/data-service-admin/apis/*/versions/*/review")
                    .hasAuthority("API_APPROVE")
                .requestMatchers(HttpMethod.POST, "/data-service-admin/apis/*/versions/*/rollback")
                    .hasAuthority("API_APPROVE")
                .requestMatchers(HttpMethod.POST, "/data-service-admin/apis/*/status")
                    .hasAuthority("API_APPROVE")
                .requestMatchers("/data-service-admin/apis/**")
                    .hasAuthority("API_MANAGE")
                .requestMatchers(HttpMethod.GET, "/data-service-admin/applications/**")
                    .hasAuthority("APPLICATION_READ")
                .requestMatchers("/data-service-admin/applications/**")
                    .hasAuthority("APPLICATION_MANAGE")
                .requestMatchers("/data-service-admin/call-logs/**")
                    .hasAuthority("AUDIT_READ")
                .requestMatchers(HttpMethod.GET, "/data-service-admin/runtime/**")
                    .hasAuthority("AUDIT_READ")
                .requestMatchers("/data-service-admin/runtime/**")
                    .hasAuthority("API_MANAGE")
                .requestMatchers(HttpMethod.GET, "/data-service-admin/slo/**")
                    .hasAuthority("AUDIT_READ")
                .requestMatchers("/data-service-admin/slo/**")
                    .hasAuthority("API_MANAGE")
                .requestMatchers(HttpMethod.GET, "/data-service-admin/governance/**")
                    .hasAuthority("GOVERNANCE_READ")
                .requestMatchers("/data-service-admin/governance/**")
                    .hasAuthority("GOVERNANCE_MANAGE")
                .requestMatchers(HttpMethod.GET, "/data-service-admin/change-requests/**")
                    .hasAuthority("CHANGE_APPROVAL_READ")
                .requestMatchers("/data-service-admin/change-requests/**")
                    .hasAuthority("CHANGE_APPROVAL_HANDLE")
                .requestMatchers(HttpMethod.GET, "/data-service-admin/subscriptions/**")
                    .hasAuthority("SUBSCRIPTION_READ")
                .requestMatchers(HttpMethod.POST, "/data-service-admin/subscriptions/*/review")
                    .hasAuthority("SUBSCRIPTION_APPROVE")
                .requestMatchers(HttpMethod.POST, "/data-service-admin/subscriptions/*/suspend")
                    .hasAuthority("SUBSCRIPTION_APPROVE")
                .requestMatchers("/data-service-admin/subscriptions/**")
                    .hasAuthority("SUBSCRIPTION_MANAGE")
                .requestMatchers(HttpMethod.GET, "/data-service-admin/developer-portal/**")
                    .hasAuthority("API_READ")
                .requestMatchers(HttpMethod.GET, "/data-service-admin/admin-users/**")
                    .hasAuthority("USER_READ")
                .requestMatchers("/data-service-admin/admin-users/**")
                    .hasAuthority("USER_MANAGE")
                .requestMatchers("/data-service-admin/auth/**").authenticated()
                .requestMatchers("/actuator/**").hasAuthority("AUDIT_READ")
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").authenticated()
                .anyRequest().denyAll()
            )
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint((request, response, exception) ->
                    writeError(response, objectMapper, 401, "请先登录"))
                .accessDeniedHandler((request, response, exception) ->
                    writeError(response, objectMapper, 403, "当前账号没有该操作权限"))
            )
            .addFilterBefore(adminAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private static void writeError(
        HttpServletResponse response,
        ObjectMapper objectMapper,
        int status,
        String message
    ) throws java.io.IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), ApiResponse.error(status, message));
    }
}
