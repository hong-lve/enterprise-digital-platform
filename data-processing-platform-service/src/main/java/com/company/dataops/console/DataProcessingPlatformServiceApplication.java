package com.company.dataops.console;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@MapperScan("com.company.dataops.console.mapper")
@SpringBootApplication
@EnableScheduling
public class DataProcessingPlatformServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(DataProcessingPlatformServiceApplication.class, args);
    }
}
