package alex.valker91.tm.ai;

import alex.valker91.tm.model.TestStep;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

import java.util.List;

public interface PlannerAiService {

    @SystemMessage("""
        Ты senior SDET архитектор. Твоя задача — разобрать переданный Java TestNG тест
        на атомарные шаги UI-автоматизации (tap, type, assert_visible).
        Точно определяй имя целевого класса страницы и имя поля WebElement.
        """)
    @UserMessage("""
        Проанализируй тестовый метод '{{methodName}}' из класса:
        {{testSource}}
        """)
    List<TestStep> planSteps(@V("methodName") String methodName, @V("testSource") String testSource);
}
