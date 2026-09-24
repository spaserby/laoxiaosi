package com.law.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.redisson.api.RedissonClient;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 附件图片过滤金标：尺寸/纯色噪声过滤是 token 成本闸的第一道
 */
class ChatFileServiceFilterTest {

    private ChatFileService newService() {
        FileProperties props = new FileProperties();
        return new ChatFileService(Mockito.mock(RedissonClient.class), props,
                Mockito.mock(ImageCaptionService.class));
    }

    @Test
    @DisplayName("过小图片（图标/分隔线）被过滤")
    void tinyImageRejected() {
        ChatFileService svc = newService();
        assertFalse(svc.meaningful(new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB)));
        assertFalse(svc.meaningful(new BufferedImage(63, 200, BufferedImage.TYPE_INT_RGB)));
    }

    @Test
    @DisplayName("纯色背景图被过滤，有内容图通过")
    void solidColorRejected() {
        ChatFileService svc = newService();
        BufferedImage solid = new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 200; x++) for (int y = 0; y < 200; y++) solid.setRGB(x, y, 0xFFFFFF);
        assertFalse(svc.meaningful(solid));

        BufferedImage content = new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 200; x++) for (int y = 0; y < 200; y++) {
            content.setRGB(x, y, (x + y) % 2 == 0 ? 0x000000 : 0xFFFFFF);   // 黑白交错 = 有内容
        }
        assertTrue(svc.meaningful(content));
    }
}
