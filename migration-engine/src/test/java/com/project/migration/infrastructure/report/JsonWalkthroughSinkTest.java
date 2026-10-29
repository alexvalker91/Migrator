package com.project.migration.infrastructure.report;

import com.project.migration.domain.model.agent.AndroidWalkthrough;
import com.project.migration.domain.model.agent.StateObservation;
import com.project.migration.domain.model.agent.WalkthroughAction;
import com.project.migration.domain.model.agent.WalkthroughStep;
import com.project.migration.domain.model.code.TestMethodRef;
import com.project.migration.domain.model.screen.ScreenId;
import com.project.migration.domain.model.screen.ScreenState;
import com.project.migration.domain.model.screen.ScreenStateKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class JsonWalkthroughSinkTest {

    @TempDir
    Path reports;

    @Test
    void writesOneReadableFilePerTestAndReplacesItOnASecondSave() throws IOException {
        JsonWalkthroughSink sink = new JsonWalkthroughSink(reports);
        TestMethodRef test = new TestMethodRef("com.bank.tests.CardsTest", "openCardDetails");
        AndroidWalkthrough walkthrough = new AndroidWalkthrough(test,
                List.of(new WalkthroughStep(WalkthroughAction.TAP, "cards-tile", Optional.empty())),
                List.of(new StateObservation(ScreenStateKey.of(new ScreenId("HomePage"), ScreenState.DEFAULT),
                        List.of("Cards"))));

        sink.save("com.bank.tests.CardsTest", walkthrough);
        sink.save("com.bank.tests.CardsTest", new AndroidWalkthrough(test, List.of(), List.of()));

        Path file = reports.resolve("CardsTest/walkthroughs/openCardDetails.json");
        assertThat(file).exists();
        assertThat(Files.readString(file)).contains("\"steps\" : [ ]").doesNotContain("cards-tile");
        sink.save("com.bank.tests.CardsTest", walkthrough);
        assertThat(Files.readString(file)).contains("\"action\" : \"TAP\"").contains("cards-tile")
                .contains("\"visible_texts\"");
    }
}
