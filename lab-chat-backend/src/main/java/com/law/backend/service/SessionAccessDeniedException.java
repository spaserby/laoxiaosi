package com.law.backend.service;

/**
 * 会话越权访问（P0 安全整改）：请求方与会话归属不符时抛出，控制器映射为 403。
 */
public class SessionAccessDeniedException extends RuntimeException {

    public SessionAccessDeniedException() {
        super("无权访问该会话");
    }
}
