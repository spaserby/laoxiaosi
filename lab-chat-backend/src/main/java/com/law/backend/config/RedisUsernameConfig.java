package com.law.backend.config;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redis 账号配置归一化。
 * <p>
 * 云 Redis（阿里云 Tair 等）为"账号+密码"体系：部署时注入环境变量
 * {@code REDIS_USERNAME} 即可被 spring.data.redis.username 绑定并生效。
 * <p>
 * 自建/本地 Redis（5.0 及以下无账号体系）不注入该变量时，yml 占位符
 * {@code ${REDIS_USERNAME:}} 会绑定成空串；而 Redisson 对空串会按 ACL 账号
 * 处理、发出 {@code AUTH "" <pwd>}，在 5.0 上直接连接失败（实测）。
 * 因此这里在 RedisProperties 被消费前把空白用户名归一为 null——等价于"未配置"，
 * 客户端退回仅密码 AUTH。
 */
@Configuration
public class RedisUsernameConfig {

    /** 静态注册：BeanPostProcessor 需在普通 Bean 之前就绪（静态 @Bean 是官方推荐写法） */
    @Bean
    static BeanPostProcessor emptyRedisUsernameNormalizer() {
        return new EmptyRedisUsernameNormalizer();
    }

    /** Spring 6 的 BeanPostProcessor 两方法均为 default，无法用 lambda，需具名实现 */
    static class EmptyRedisUsernameNormalizer implements BeanPostProcessor {
        @Override
        public Object postProcessBeforeInitialization(Object bean, String beanName) {
            if (bean instanceof RedisProperties props
                    && props.getUsername() != null && props.getUsername().isBlank()) {
                props.setUsername(null);
            }
            return bean;
        }
    }
}
