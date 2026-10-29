package com.project.migration.application.healing;

/**
 * Kinds of source change the model may propose for a failing test.
 */
public enum ChangeKind {
    REPLACE_METHOD_BODY,
    ADD_IOS_LOCATOR,
    INSERT_CALL_BEFORE,
    ADD_IMPORT
}
