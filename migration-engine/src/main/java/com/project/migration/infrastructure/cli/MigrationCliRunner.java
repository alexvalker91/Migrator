package com.project.migration.infrastructure.cli;

import com.project.migration.domain.exception.MigrationException;
import com.project.migration.domain.model.config.EngineConfig;
import com.project.migration.domain.model.orchestration.ClassMigrationOutcome;
import com.project.migration.domain.model.orchestration.MigrationExitStatus;
import com.project.migration.domain.model.orchestration.TestClassMigrationRequest;
import com.project.migration.infrastructure.config.JsonEngineConfigLoader;
import com.project.migration.infrastructure.llm.DialSettings;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Command line entry: {@code java -jar migration-engine-cli.jar CardsTest --project-root <dir>}. Classes run one
 * after the other; an aborted class stops the batch. The exit code is the highest code of the classes, 64 for a wrong
 * command line, configuration or environment, 70 for an infrastructure failure.
 */
public final class MigrationCliRunner {

    static final int EXIT_USAGE = 64;
    static final int EXIT_INFRASTRUCTURE = 70;
    private static final Pattern ENV_REFERENCE = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    /** Builds the engine for a configuration; replaced in tests. */
    @FunctionalInterface
    interface EngineFactory {
        Engine create(EngineConfig config, Map<String, String> environment);
    }

    private final PrintStream out;
    private final PrintStream err;
    private final Map<String, String> environment;
    private final Path workingDirectory;
    private final EngineFactory engineFactory;
    private final boolean installShutdownHook;

    MigrationCliRunner(PrintStream out, PrintStream err, Map<String, String> environment, Path workingDirectory,
                       EngineFactory engineFactory, boolean installShutdownHook) {
        this.out = out;
        this.err = err;
        this.environment = environment;
        this.workingDirectory = workingDirectory;
        this.engineFactory = engineFactory;
        this.installShutdownHook = installShutdownHook;
    }

    public static void main(String[] args) {
        int exitCode = new MigrationCliRunner(System.out, System.err, System.getenv(), Path.of("").toAbsolutePath(),
                MigrationCliRunner::assemble, true).run(args);
        System.exit(exitCode);
    }

    private static Engine assemble(EngineConfig config, Map<String, String> environment) {
        return EngineAssembly.assemble(config, new DialSettings(
                environment.getOrDefault(DialSettings.HOST_ENV, DialSettings.DEFAULT_HOST), environment.get(DialSettings.API_KEY_ENV)));
    }

    int run(String[] args) {
        try {
            CliArguments arguments = CliArguments.parse(args, workingDirectory);
            if (arguments.help()) {
                out.print(CliArguments.USAGE);
                return 0;
            }
            return migrate(arguments);
        } catch (UsageException e) {
            err.println("Error: " + e.getMessage());
            err.println();
            err.print(CliArguments.USAGE);
            return EXIT_USAGE;
        } catch (MigrationException e) {
            err.println("Migration infrastructure failure: " + e.getMessage());
            return EXIT_INFRASTRUCTURE;
        } catch (RuntimeException e) {
            err.println("Unexpected failure: " + e);
            e.printStackTrace(err);
            return EXIT_INFRASTRUCTURE;
        }
    }

    private int migrate(CliArguments arguments) {
        EngineConfig config = loadConfig(arguments);
        requireEnvironment(config);
        Path root = config.projectRoot();
        if (!Files.isDirectory(root)) {
            throw new UsageException("project root " + root + " is not a directory");
        }
        try (ProjectLock ignored = ProjectLock.acquire(config.migrationDir(), String.join(",", arguments.classes()))) {
            Engine engine = engineFactory.create(config, environment);
            Thread hook = installShutdownHook ? Thread.ofPlatform().unstarted(engine::close) : null;
            if (hook != null) {
                Runtime.getRuntime().addShutdownHook(hook);
            }
            try {
                return runClasses(arguments, engine);
            } finally {
                engine.close();
                if (hook != null) {
                    try {
                        Runtime.getRuntime().removeShutdownHook(hook);
                    } catch (IllegalStateException shuttingDown) {
                        // the JVM is already going down; the hook runs
                    }
                }
            }
        }
    }

    private int runClasses(CliArguments arguments, Engine engine) {
        ClassResolver resolver = new ClassResolver(engine.analyzer());
        List<String> classes = new ArrayList<>();
        for (String name : arguments.classes()) {
            String fqcn = resolver.resolve(name);
            if (!classes.contains(fqcn)) {
                classes.add(fqcn);
            }
        }
        int exitCode = 0;
        for (String fqcn : classes) {
            ClassMigrationOutcome outcome = engine.orchestrator().migrate(
                    new TestClassMigrationRequest(fqcn, arguments.push(), arguments.pushOnRegression()));
            ConsoleSummary.print(out, outcome);
            exitCode = Math.max(exitCode, outcome.status().exitCode());
            if (outcome.status() == MigrationExitStatus.ABORTED && classes.indexOf(fqcn) < classes.size() - 1) {
                err.println("Stopping: the remaining classes are not started after an aborted migration.");
                break;
            }
        }
        return exitCode;
    }

    private EngineConfig loadConfig(CliArguments arguments) {
        try {
            return new JsonEngineConfigLoader().load(arguments.projectRoot(), arguments.showReasoning(), arguments.config());
        } catch (IllegalArgumentException e) {
            throw new UsageException(e.getMessage());
        }
    }

    /** Fails before any work when a variable the run needs is missing. */
    private void requireEnvironment(EngineConfig config) {
        Set<String> missing = new LinkedHashSet<>();
        if (isBlank(environment.get(DialSettings.API_KEY_ENV))) {
            missing.add(DialSettings.API_KEY_ENV);
        }
        config.mcpServers().values().forEach(server -> server.env().values().forEach(value -> {
            Matcher matcher = ENV_REFERENCE.matcher(value);
            while (matcher.find()) {
                if (isBlank(environment.get(matcher.group(1)))) {
                    missing.add(matcher.group(1));
                }
            }
        }));
        if (!missing.isEmpty()) {
            throw new UsageException("environment variable(s) not set: " + String.join(", ", missing));
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
