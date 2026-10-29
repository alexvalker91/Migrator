package com.project.migration.infrastructure.llm;

import com.project.migration.domain.model.llm.AssistantMessage;
import com.project.migration.domain.model.llm.LlmMessage;
import com.project.migration.domain.model.llm.SystemMessage;
import com.project.migration.domain.model.llm.ToolCall;
import com.project.migration.domain.model.llm.ToolResultMessage;
import com.project.migration.domain.model.llm.UserMessage;
import com.project.migration.domain.model.media.ImageAttachment;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Domain conversation ↔ LangChain4j messages. Screenshots are sent inline (base64) at high detail: self-healing
 * must read small text on failure screenshots.
 */
final class LlmMessageMapper {

    private LlmMessageMapper() {
    }

    static List<ChatMessage> toChatMessages(List<LlmMessage> messages) {
        return messages.stream().map(LlmMessageMapper::toChatMessage).toList();
    }

    static ChatMessage toChatMessage(LlmMessage message) {
        return switch (message) {
            case SystemMessage system -> dev.langchain4j.data.message.SystemMessage.from(system.text());
            case UserMessage user -> toUserMessage(user);
            case AssistantMessage assistant -> toAiMessage(assistant);
            case ToolResultMessage result ->
                    ToolExecutionResultMessage.from(result.toolCallId(), result.toolName(), result.content());
        };
    }

    /** Tool calls requested by the model; ids are generated when a provider omits them. */
    static List<ToolCall> toToolCalls(AiMessage message) {
        if (!message.hasToolExecutionRequests()) {
            return List.of();
        }
        return message.toolExecutionRequests().stream()
                .map(request -> new ToolCall(
                        request.id() == null || request.id().isBlank() ? "call_" + UUID.randomUUID() : request.id(),
                        request.name(),
                        request.arguments() == null ? "{}" : request.arguments()))
                .toList();
    }

    private static dev.langchain4j.data.message.UserMessage toUserMessage(UserMessage user) {
        List<Content> contents = new ArrayList<>();
        contents.add(TextContent.from(user.text()));
        for (ImageAttachment image : user.images()) {
            contents.add(ImageContent.from(Base64.getEncoder().encodeToString(image.data()), image.mimeType(),
                    ImageContent.DetailLevel.HIGH));
        }
        return dev.langchain4j.data.message.UserMessage.from(contents);
    }

    private static AiMessage toAiMessage(AssistantMessage assistant) {
        List<ToolExecutionRequest> requests = assistant.toolCalls().stream()
                .map(call -> ToolExecutionRequest.builder().id(call.id()).name(call.name())
                        .arguments(call.argumentsJson()).build())
                .toList();
        AiMessage.Builder builder = AiMessage.builder();
        assistant.text().ifPresent(builder::text);
        if (!requests.isEmpty()) {
            builder.toolExecutionRequests(requests);
        }
        return builder.build();
    }
}
