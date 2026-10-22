package com.law.backend.attachment;

import com.law.backend.service.FileProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AttachmentEmbeddingTest {
    @Test void timesOutHungProviderAndRejectsAdditionalCallsWithoutCreatingMoreThreads() throws Exception {
        var properties = new FileProperties(); properties.setEmbeddingTimeoutSeconds(1);
        var provider = mock(EmbeddingModel.class); var release = new CountDownLatch(1);
        when(provider.embed(anyString())).thenAnswer(invocation -> {
            while (release.getCount() > 0) { try { release.await(); } catch (InterruptedException ignored) { } }
            return new float[]{1, 0};
        });
        var embedding = new AttachmentEmbedding(provider, properties);
        try {
            assertThrows(IllegalStateException.class, () -> embedding.query("合成问题一"));
            assertThrows(IllegalStateException.class, () -> embedding.query("合成问题二"));
            long start = System.nanoTime();
            assertThrows(IllegalStateException.class, () -> embedding.query("合成问题三"));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 500);
            verify(provider, times(2)).embed(anyString());
        } finally { release.countDown(); embedding.close(); }
    }
}
