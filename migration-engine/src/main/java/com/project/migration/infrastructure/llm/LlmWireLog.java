package com.project.migration.infrastructure.llm;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.FileAppender;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Routes LangChain4j's HTTP request/response log to a dedicated file (e.g.
 * {@code migration_reports/<Class>/llm_wire.log}) for debugging, enabled with
 * {@code -Dmigration.llm.wireLog=true}. Off by default: requests carry whole prompts and base64 screenshots.
 * LangChain4j masks the {@code api-key} / {@code Authorization} headers itself.
 */
public final class LlmWireLog implements AutoCloseable {

    public static final String PROPERTY = "migration.llm.wireLog";
    static final String HTTP_LOGGER = "dev.langchain4j.http.client.log";

    private final Logger logger;
    private final FileAppender<ILoggingEvent> appender;

    private LlmWireLog(Logger logger, FileAppender<ILoggingEvent> appender) {
        this.logger = logger;
        this.appender = appender;
    }

    public static boolean requested() {
        return Boolean.getBoolean(PROPERTY);
    }

    public static LlmWireLog enable(Path file) {
        if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext context)) {
            throw new IllegalStateException("LLM wire log needs Logback as the SLF4J backend");
        }
        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern("%d{yyyy-MM-dd HH:mm:ss.SSS} %msg%n");
        encoder.start();

        FileAppender<ILoggingEvent> appender = new FileAppender<>();
        appender.setContext(context);
        appender.setName("llm-wire-" + file);
        appender.setFile(file.toAbsolutePath().toString());
        appender.setAppend(true);
        appender.setEncoder(encoder);
        appender.start();

        Logger logger = context.getLogger(HTTP_LOGGER);
        logger.setLevel(Level.INFO);
        logger.setAdditive(false);
        logger.addAppender(appender);
        return new LlmWireLog(logger, appender);
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(null);
        logger.setAdditive(true);
    }
}
