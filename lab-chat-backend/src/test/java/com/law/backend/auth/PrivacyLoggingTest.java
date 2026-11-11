package com.law.backend.auth;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.law.backend.model.*;
import com.law.backend.safety.*;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.ai.embedding.EmbeddingModel;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PrivacyLoggingTest {
    @Test void routingSafetyAndMockSmsNeverLogOriginalInputPhoneOrCode() {
        var appender=new ListAppender<ILoggingEvent>(); appender.start();
        var loggers=List.of(ChainModelRouter.class,SafetyGuard.class,MockSmsSender.class).stream().map(c -> (Logger)LoggerFactory.getLogger(c)).toList();
        loggers.forEach(l -> l.addAppender(appender));
        try {
            var p=new ModelRouteProperties(); p.setRoutes(Map.of("default","test/model")); p.getRouter().setLayers(List.of());
            var router=new ChainModelRouter(mock(ModelRegistry.class),p,mock(EmbeddingModel.class),mock(ApplicationContext.class));
            String marker="SYNTHETIC_PRIVATE_CONSULTATION_MARKER"; router.route(marker);
            var safety=new SafetyProperties(); safety.setRefusalKeywords(List.of(marker)); new SafetyGuard(safety).checkRefusal(marker);
            String phone="1"+"9".repeat(10), code="123"+"456"; new MockSmsSender().send(phone,code);
            String logs=appender.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("",(a,b)->a+"\n"+b);
            assertFalse(logs.contains(marker)); assertFalse(logs.contains(phone)); assertFalse(logs.contains(code));
            assertEquals("199****9999",com.law.backend.util.PrivacyLog.phone(phone));
        } finally { loggers.forEach(l -> l.detachAppender(appender)); appender.stop(); }
    }
}
