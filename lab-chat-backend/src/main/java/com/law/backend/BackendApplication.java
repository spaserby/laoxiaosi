package com.law.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * backend 启动类
 * <p>
 * 从 
 * Redis 会话记忆持久化与 Function Calling 工具调用。
 * 启动后可用 curl 测试：
 * <pre>
 * curl -N "http://localhost:8082/chat/stream?sessionId=test1&userMessage=你好"
 * </pre>
 */
@SpringBootApplication
public class BackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(BackendApplication.class, args);
    }
}
