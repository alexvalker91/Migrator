package com.project.migration.infrastructure.llm;

import dev.langchain4j.model.chat.ChatModel;

/**
 * Creates the LangChain4j model of one DIAL deployment. Seam for tests of {@link DialTieredLlmRouter}.
 */
public interface ChatModelFactory {

    ChatModel create(String modelId);
}
