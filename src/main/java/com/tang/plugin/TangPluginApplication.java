package com.tang.plugin;

import com.tang.plugin.annotation.EnableRyFeignClients;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableRetry
@EnableScheduling
@EnableAsync
@EnableRyFeignClients
@SpringBootApplication
public class TangPluginApplication {

    public static void main(String[] args) {
        SpringApplication.run(TangPluginApplication.class, args);
    }
}
