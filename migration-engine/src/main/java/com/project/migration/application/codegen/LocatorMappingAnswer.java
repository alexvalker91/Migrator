package com.project.migration.application.codegen;

import java.util.List;

/**
 * Structured answer of a locator-mapping call.
 */
public record LocatorMappingAnswer(List<FieldAnswer> fields) {

    /**
     * @param field    the id given in the request (class and field name)
     * @param strategy {@code accessibility}, {@code iOSNsPredicate} or {@code iOSClassChain}
     */
    public record FieldAnswer(String field, boolean found, String strategy, String value) {
    }
}
