package com.project.migration.domain.port;

import com.project.migration.domain.exception.TestRunnerException;
import com.project.migration.domain.model.Platform;
import com.project.migration.domain.model.code.TestMethodRef;
import com.project.migration.domain.model.run.CompilationResult;
import com.project.migration.domain.model.run.TestClassRunResult;
import com.project.migration.domain.model.run.TestRunResult;

/**
 * Builds and runs the target project's tests (Maven + TestNG). Failing tests and compiler errors are results;
 * {@link TestRunnerException} only means Maven could not be run or produced no evaluable output at all.
 */
public interface TestRunnerPort {

    /** {@code mvn test-compile} of the whole target project. */
    CompilationResult compile();

    /** {@code mvn test -Dtest=Class#method -Dplatform=<platform>}, one run. */
    TestRunResult runTestMethod(TestMethodRef test, Platform platform);

    /** {@code mvn test -Dtest=Class -Dplatform=<platform>}, one run of all methods. */
    TestClassRunResult runTestClass(String testClassFqcn, Platform platform);
}
