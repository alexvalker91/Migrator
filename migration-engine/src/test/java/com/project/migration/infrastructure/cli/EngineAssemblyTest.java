package com.project.migration.infrastructure.cli;

import com.project.migration.domain.model.config.EngineConfig;
import com.project.migration.infrastructure.config.JsonEngineConfigLoader;
import com.project.migration.infrastructure.llm.DialSettings;
import com.project.migration.testsupport.BankFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class EngineAssemblyTest {

    @TempDir
    Path project;

    @Test
    void theWholeObjectGraphIsBuiltFromTheConfigurationWithoutTouchingTheNetwork() {
        BankFixture.copyTo(project);
        EngineConfig config = new JsonEngineConfigLoader().load(project, false, Optional.empty())
                .withFramework(BankFixture.FRAMEWORK);

        Engine engine = EngineAssembly.assemble(config, new DialSettings("http://localhost:9", "key"));

        assertThat(engine.orchestrator()).isNotNull();
        assertThat(engine.analyzer().listTestMethods("com.bank.tests.CardsTest")).hasSize(4);
        engine.close();
        engine.close();
    }
}
