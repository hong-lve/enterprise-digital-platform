package com.company.dataops.console.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.dataops.console.entity.UserEntity;
import com.company.dataops.console.mapper.UserMapper;
import com.company.dataops.console.security.LocalAuthorityService;
import java.util.ArrayList;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, CorsConfigurationSource corsConfigurationSource) throws Exception {
        return http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsConfigurationSource))
            .authorizeHttpRequests(auth -> auth
                // No dev/prod Spring profile split exists in this project (one
                // application.yml, no @Profile anywhere) to gate springdoc
                // behind, and it was previously exempted from auth entirely -
                // requiring the same session everything else needs keeps the
                // API surface (endpoint list, request/response shapes) from
                // being readable by anyone who hasn't logged in, while still
                // working for anyone who has (same-origin session cookie).
                .requestMatchers("/auth/login", "/auth/2fa/verify", "/actuator/health/**").permitAll()
                .anyRequest().authenticated()
            )
            .logout(logout -> logout.logoutUrl("/auth/logout").logoutSuccessHandler((request, response, authentication) -> response.setStatus(200)))
            .build();
    }

    // Arbitrary but validly-formatted bcrypt hash (this is admin's own seeded
    // hash from V22, reused only for its shape) - matched against whenever
    // the username doesn't exist, purely to spend the same bcrypt cost a
    // real lookup would. Its actual plaintext is irrelevant: a nonexistent
    // username is rejected via the null check below regardless of whether
    // this happens to match.
    private static final String DUMMY_HASH = "$2a$10$eGrc.68zNEpIlWgcfZxTwOOop/TCMr2H10B8m6BYOyKvOmG/.HXjW";

    @Bean
    AuthenticationManager authenticationManager(LocalAuthorityService authorityService, UserMapper userMapper, PasswordEncoder passwordEncoder) {
        return authentication -> {
            String username = authentication.getName();
            String password = String.valueOf(authentication.getCredentials());
            UserEntity user = userMapper.selectOne(new LambdaQueryWrapper<UserEntity>().eq(UserEntity::getUsername, username));
            // bcrypt.matches() always runs, on a real hash either way - short-
            // circuiting it away for a nonexistent/disabled user (the old
            // `user == null || ... || matches(...)` order) made those requests
            // return measurably faster than a real "wrong password" attempt,
            // letting an attacker time responses to enumerate valid usernames.
            boolean passwordMatches = passwordEncoder.matches(password, user != null ? user.getPasswordHash() : DUMMY_HASH);
            if (user == null || !"ENABLED".equals(user.getStatus()) || !passwordMatches) {
                throw new org.springframework.security.authentication.BadCredentialsException("Invalid username or password");
            }
            var authorities = new ArrayList<SimpleGrantedAuthority>();
            authorityService.permissionsFor(username).forEach(permission -> authorities.add(new SimpleGrantedAuthority(permission)));
            return new UsernamePasswordAuthenticationToken(username, null, authorities);
        };
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${platform.web.frontend-url}") String frontendUrl) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.addAllowedOrigin(frontendUrl);
        configuration.addAllowedHeader("*");
        configuration.addAllowedMethod("*");
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
