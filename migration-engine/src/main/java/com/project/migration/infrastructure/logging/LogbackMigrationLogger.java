package com.project.migration.infrastructure.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import ch.qos.logback.core.FileAppender;
import ch.qos.logback.core.OutputStreamAppender;
import com.project.migration.domain.model.MigrationStage;
import com.project.migration.domain.model.code.CodeNames;
import com.project.migration.domain.model.code.TestMethodRef;
import com.project.migration.domain.model.llm.LlmTier;
import com.project.migration.domain.port.MigrationLoggerPort;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * {@link MigrationLoggerPort} on a private Logback {@link LoggerContext}, isolated from the global logging
 * configuration of LangChain4j / MCP SDK. Regular lines go to the console and the session file; reasoning goes
 * to the session file always and to the console only with {@code showReasoning}.
 */
public final class LogbackMigrationLogger implements MigrationLoggerPort, AutoCloseable {

    static final String LOG_FILE_NAME = "execution.log";
    static final String REASONING_HEADER = "[🧠 LLM REASONING]";

    private static final String PATTERN = "%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level %msg%n%ex";
    private static final String NO_STAGE = "-";
    private static final String NO_TEST = "-";

    private final Path reportsDir;
    private final LoggerContext context;
    private final Logger mainLogger;
    private final Logger reasoningLogger;
    private final OutputStreamAppender<ILoggingEvent> consoleAppender;

    private FileAppender<ILoggingEvent> fileAppender;
    private String stageTag = NO_STAGE;
    private String testTag = NO_TEST;

    public LogbackMigrationLogger(Path reportsDir, boolean showReasoning) {
        this(reportsDir, showReasoning, System.out);
    }

    LogbackMigrationLogger(Path reportsDir, boolean showReasoning, OutputStream console) {
        this.reportsDir = Objects.requireNonNull(reportsDir, "reportsDir");
        this.context = new LoggerContext();
        context.setName("migration-engine");
        // a standalone context gets no MDC adapter from the SLF4J provider; events fail to append without one
        context.setMDCAdapter(new LogbackMDCAdapter());
        context.start();

        consoleAppender = new OutputStreamAppender<>();
        consoleAppender.setContext(context);
        consoleAppender.setName("console");
        consoleAppender.setEncoder(newEncoder());
        consoleAppender.setOutputStream(new NonClosingOutputStream(Objects.requireNonNull(console, "console")));
        consoleAppender.start();

        mainLogger = configureLogger("migration");
        mainLogger.addAppender(consoleAppender);

        reasoningLogger = configureLogger("migration.reasoning");
        if (showReasoning) {
            reasoningLogger.addAppender(consoleAppender);
        }
    }

    @Override
    public synchronized void startSession(String testClassFqcn) {
        endSession();
        String simpleName = CodeNames.simpleName(Objects.requireNonNull(testClassFqcn, "testClassFqcn"));
        Path logFile = reportsDir.resolve(simpleName).resolve(LOG_FILE_NAME);
        try {
            Files.createDirectories(logFile.getParent());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create report directory " + logFile.getParent(), e);
        }
        fileAppender = new FileAppender<>();
        fileAppender.setContext(context);
        fileAppender.setName("session-file");
        fileAppender.setFile(logFile.toString());
        fileAppender.setAppend(true);
        fileAppender.setEncoder(newEncoder());
        fileAppender.start();
        mainLogger.addAppender(fileAppender);
        reasoningLogger.addAppender(fileAppender);

        stageTag = NO_STAGE;
        testTag = NO_TEST;
        info("===== Migration session started: " + testClassFqcn + " =====");
    }

    @Override
    public synchronized void enterStage(MigrationStage stage) {
        stageTag = stage.name();
        info(">>> " + stage.displayName());
    }

    @Override
    public synchronized void enterTest(TestMethodRef test) {
        testTag = test.simpleClassName() + "#" + test.methodName();
    }

    @Override
    public synchronized void exitTest() {
        testTag = NO_TEST;
    }

    @Override
    public synchronized void info(String message) {
        mainLogger.info(line(message));
    }

    @Override
    public synchronized void warn(String message) {
        mainLogger.warn(line(message));
    }

    @Override
    public synchronized void error(String message, Throwable cause) {
        mainLogger.error(line(message), cause);
    }

    @Override
    public synchronized void reasoning(LlmTier tier, String purpose, String reasoningText) {
        if (reasoningText == null || reasoningText.isBlank()) {
            return;
        }
        StringBuilder block = new StringBuilder(line(REASONING_HEADER + " " + tier + " · " + purpose));
        reasoningText.strip().lines().forEach(l -> block.append(System.lineSeparator()).append("    │ ").append(l));
        reasoningLogger.info(block.toString());
    }

    @Override
    public synchronized void endSession() {
        if (fileAppender == null) {
            return;
        }
        info("===== Migration session finished =====");
        mainLogger.detachAppender(fileAppender);
        reasoningLogger.detachAppender(fileAppender);
        fileAppender.stop();
        fileAppender = null;
        stageTag = NO_STAGE;
        testTag = NO_TEST;
    }

    /** Ends the session and releases the logging context. */
    @Override
    public synchronized void close() {
        endSession();
        consoleAppender.stop();
        context.stop();
    }

    private String line(String message) {
        return "[" + stageTag + "] [" + testTag + "] " + message;
    }

    private Logger configureLogger(String name) {
        Logger logger = context.getLogger(name);
        logger.setLevel(Level.INFO);
        logger.setAdditive(false);
        return logger;
    }

    private PatternLayoutEncoder newEncoder() {
        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern(PATTERN);
        encoder.setCharset(StandardCharsets.UTF_8);
        encoder.start();
        return encoder;
    }

    /** Stopping the console appender must not close {@code System.out}. */
    private static final class NonClosingOutputStream extends FilterOutputStream {

        NonClosingOutputStream(OutputStream delegate) {
            super(delegate);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }
}
