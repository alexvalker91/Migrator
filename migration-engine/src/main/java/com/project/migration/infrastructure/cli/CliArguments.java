package com.project.migration.infrastructure.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Parsed command line. No library: the grammar is a handful of flags.
 *
 * @param classes     test classes to migrate, in the order given (simple name, {@code Name.java} or FQCN)
 * @param projectRoot root of the target project
 */
record CliArguments(
        List<String> classes,
        Path projectRoot,
        Optional<Path> config,
        boolean showReasoning,
        boolean push,
        boolean pushOnRegression,
        boolean help) {

    static final String USAGE = """
            Usage: java -jar migration-engine-cli.jar <TestClass> [<TestClass> ...] [options]

            <TestClass>             simple name (CardsTest), file name (CardsTest.java) or fully qualified name

            Options:
              --project-root <dir>  root of the target project (default: current directory)
              --config <file>       JSON file merged over the engine defaults
              --show-reasoning      print the LLM reasoning blocks to the console (always kept in execution.log)
              --no-push             commit on the migration branch but do not push it
              --push-on-regression  push the branch even when the final iOS run or the Android guard is red
              --help                show this text

            Environment: DIAL_API_KEY (required), DIAL_HOST, and the variables named in the config (${VAR}).
            Exit codes: 0 success, 1 some tests need a manual fix, 2 final checks failed, 3 aborted,
                        64 wrong usage or configuration, 70 infrastructure failure.
            """;

    CliArguments {
        classes = List.copyOf(classes);
    }

    static CliArguments parse(String[] args, Path workingDirectory) {
        List<String> classes = new ArrayList<>();
        Path projectRoot = workingDirectory;
        Optional<Path> config = Optional.empty();
        boolean showReasoning = false;
        boolean push = true;
        boolean pushOnRegression = false;
        boolean help = false;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            String name = arg.contains("=") && arg.startsWith("--") ? arg.substring(0, arg.indexOf('=')) : arg;
            switch (name) {
                case "--help", "-h" -> help = true;
                case "--show-reasoning" -> showReasoning = true;
                case "--no-push" -> push = false;
                case "--push-on-regression" -> pushOnRegression = true;
                case "--project-root" -> {
                    String value = valueOf(args, i, arg, name);
                    i += arg.contains("=") ? 0 : 1;
                    projectRoot = workingDirectory.resolve(value);
                }
                case "--config" -> {
                    String value = valueOf(args, i, arg, name);
                    i += arg.contains("=") ? 0 : 1;
                    config = Optional.of(workingDirectory.resolve(value));
                }
                default -> {
                    if (arg.startsWith("-")) {
                        throw new UsageException("unknown option " + arg);
                    }
                    classes.add(arg);
                }
            }
        }
        if (!help && classes.isEmpty()) {
            throw new UsageException("no test class given");
        }
        return new CliArguments(classes, projectRoot.toAbsolutePath().normalize(),
                config.map(path -> path.toAbsolutePath().normalize()), showReasoning, push, pushOnRegression, help);
    }

    private static String valueOf(String[] args, int index, String arg, String name) {
        if (arg.contains("=")) {
            String value = arg.substring(arg.indexOf('=') + 1);
            if (value.isBlank()) {
                throw new UsageException(name + " needs a value");
            }
            return value;
        }
        if (index + 1 >= args.length || args[index + 1].startsWith("--")) {
            throw new UsageException(name + " needs a value");
        }
        return args[index + 1];
    }
}
