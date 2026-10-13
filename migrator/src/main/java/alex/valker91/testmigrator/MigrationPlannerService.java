package alex.valker91.testmigrator;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

public interface MigrationPlannerService {
    @SystemMessage("Ты QA Architect. Анализируешь Android Page Objects и строишь строгий план миграции на iOS.")
    @UserMessage("""
        Проанализируй Java Page Object под Android:
        {{pageObjectCode}}
        
        Определи список элементов, требующих подбора локаторов под iOS.
        """)
    Models.MigrationPlan createPlan(@V("pageObjectCode") String pageObjectCode);
}
