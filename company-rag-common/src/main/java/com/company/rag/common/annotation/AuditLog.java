package com.company.rag.common.annotation;

import java.lang.annotation.*;

/**
 * 审计日志注解
 * 用于标记需要记录审计日志的方法
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AuditLog {

    /**
     * 操作类型（如 LOGIN、DELETE_DOCUMENT 等）
     */
    String actionType();

    /**
     * 操作目标类型（如 user、document、tenant 等）
     */
    String targetType() default "";

    /**
     * 操作目标 ID 的 SpEL 表达式
     */
    String targetId() default "";

    /**
     * 操作详情的 SpEL 表达式
     */
    String detail() default "";

    /**
     * 是否异步记录（数据类高吞吐为 true；管理类默认 false 同步）
     */
    boolean async() default false;
}
