package com.tang.plugin.annotation;

import org.springframework.cloud.openfeign.EnableFeignClients;

import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@EnableFeignClients(basePackages = {"com.tang"})
public @interface EnableRyFeignClients {
    String[] value() default {};
    String[] basePackages() default {"com.tang"};
    Class<?>[] basePackageClasses() default {};
    Class<?>[] defaultConfiguration() default {};
    Class<?>[] clients() default {};
}
