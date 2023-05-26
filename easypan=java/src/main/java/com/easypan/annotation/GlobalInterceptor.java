package com.easypan.annotation;

import org.springframework.web.bind.annotation.Mapping;

import java.lang.annotation.*;

@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Mapping
public @interface GlobalInterceptor {

    // 校验参数
    boolean checkParams() default false;

    // 校验登陆
    boolean checkLogin() default true;

    // 校验管理员
    boolean checkAdmin() default false;

}
