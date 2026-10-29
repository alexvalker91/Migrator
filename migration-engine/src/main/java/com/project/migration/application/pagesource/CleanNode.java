package com.project.migration.application.pagesource;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Element of the cleaned tree; attributes keep insertion order so the output is deterministic.
 */
record CleanNode(String tag, Map<String, String> attributes, List<CleanNode> children) {

    CleanNode {
        attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
        children = List.copyOf(children);
    }
}
