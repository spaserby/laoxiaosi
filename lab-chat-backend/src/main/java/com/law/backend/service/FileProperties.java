package com.law.backend.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 会话附件配置：图片限额/过滤/caption/VL 模型
 * <p>
 * 四个限额对应四个工程坑：token 爆炸（轮/文档图片上限）、无意义图噪声（尺寸/纯色过滤）、
 * caption 成本（限流并行 + 超时降级）、扫描版 PDF（页数上限防超长文档）。
 */
@Data
@ConfigurationProperties(prefix = "legal.ai.files")
public class FileProperties {

    /** 单轮咨询图片总数上限（token 成本闸：qwen-vl 单图约千级 token） */
    private int maxImagesPerTurn = 8;

    /** 单文档抽图上限（超出丢弃并提示） */
    private int maxImagesPerDoc = 6;

    /** 每文档生成 caption 的图片数上限（成本闸：其余图 caption 留空） */
    private int captionLimit = 3;

    /** 单张 caption 超时秒数（超时降级为空 caption，不阻断上传） */
    private int captionTimeoutSeconds = 8;

    /** 最小有效边长 px（小于视为图标/分隔线噪声丢弃） */
    private int minImagePx = 64;

    /** PDF 最大处理页数（防超长文档抽图爆炸） */
    private int maxPages = 30;

    /** 多模态模型名（需支持视觉输入；当前 deepseek-v4-flash，备选 qwen-vl-max） */
    private String vlModel = "deepseek-v4-flash";

    /** 多模态模型所属 provider（providers _map 键；换模型只改 vl-model + vl-provider 两项） */
    private String vlProvider = "deepseek";
}
