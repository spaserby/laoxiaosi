package com.law.backend.util;

/** 日志只保留必要元数据；不能记录验证码或咨询正文。 */
public final class PrivacyLog {
    private PrivacyLog() {}
    public static String phone(String value) {
        return value != null && value.matches("1\\d{10}") ? value.substring(0,3) + "****" + value.substring(7) : "[redacted]";
    }
}
