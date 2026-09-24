package com.law.backend.memory;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 消息记录（用于 Redis 序列化）
 * <p>
 * Spring AI 的 Message 是接口，无法直接 JSON 序列化。
 * 故用此类作为中间载体：存储消息类型与文本内容，反序列化时再还原为对应 Message 子类。
 * <p>
 * 设计取舍：
 * 不直接序列化 Message 实现类（如 UserMessage），因为 Spring AI 版本升级时
 * 实现类字段可能变动导致反序列化失败。本中间载体只保留必要字段，向前兼容。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MessageRecord implements Serializable {

    /** 消息类型：USER / ASSISTANT / SYSTEM / TOOL */
    private String type;

    /** 消息文本内容 */
    private String content;
}
