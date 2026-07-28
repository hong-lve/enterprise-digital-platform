package com.company.dataops.dataservice.security;

import com.company.dataops.dataservice.repository.AdminSecurityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class AdminBootstrapInitializer implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapInitializer.class);

    private final AdminSecurityRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final boolean enabled;
    private final String username;
    private final String password;
    private final String displayName;

    public AdminBootstrapInitializer(
        AdminSecurityRepository repository,
        PasswordEncoder passwordEncoder,
        @Value("${platform.data-service.admin.bootstrap.enabled:true}") boolean enabled,
        @Value("${platform.data-service.admin.bootstrap.username:admin}") String username,
        @Value("${platform.data-service.admin.bootstrap.password:Admin@123456}") String password,
        @Value("${platform.data-service.admin.bootstrap.display-name:系统管理员}") String displayName
    ) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.enabled = enabled;
        this.username = username;
        this.password = password;
        this.displayName = displayName;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled || repository.existsUser(username)) {
            return;
        }
        if (password == null || password.length() < 12) {
            throw new IllegalStateException("初始管理员密码至少需要 12 个字符");
        }
        repository.createBootstrapAdmin(username, passwordEncoder.encode(password), displayName);
        log.warn("已创建初始管理员账号 {}，请登录后尽快修改环境变量中的初始密码", username);
    }
}
