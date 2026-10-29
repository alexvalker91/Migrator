package com.project.migration.infrastructure.llm;

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.ModelNotFoundException;
import dev.langchain4j.exception.TimeoutException;
import dev.langchain4j.exception.UnresolvedModelServerException;
import dev.langchain4j.exception.UnsupportedFeatureException;

import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.util.Locale;

/**
 * Classifies LangChain4j failures (after its own retries) into what the router does next.
 */
final class LlmFailures {

    enum Kind {
        /** 404, 5xx, timeout, unreachable: the deployment is down; try the next one of the tier. */
        DEPLOYMENT_UNAVAILABLE,
        /** The deployment rejects {@code response_format: json_schema}; retry it with the schema in the prompt. */
        JSON_SCHEMA_UNSUPPORTED,
        /** Bad request, authentication, refusal...: another deployment would fail the same way. */
        FATAL
    }

    private LlmFailures() {
    }

    static Kind classify(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ModelNotFoundException || cause instanceof InternalServerException
                    || cause instanceof TimeoutException || cause instanceof UnresolvedModelServerException
                    || cause instanceof HttpTimeoutException || cause instanceof ConnectException) {
                return Kind.DEPLOYMENT_UNAVAILABLE;
            }
            if (cause instanceof HttpException http && (http.statusCode() == 404 || http.statusCode() >= 500)) {
                return Kind.DEPLOYMENT_UNAVAILABLE;
            }
            if (cause instanceof UnsupportedFeatureException
                    || cause instanceof InvalidRequestException && mentionsResponseFormat(cause)) {
                return Kind.JSON_SCHEMA_UNSUPPORTED;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return Kind.FATAL;
    }

    private static boolean mentionsResponseFormat(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage() == null ? "" : cause.getMessage().toLowerCase(Locale.ROOT);
            if (message.contains("response_format") || message.contains("json_schema")) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }
}
