package com.project.migration.application.pagesource;

import java.util.Map;

/**
 * Serializes the cleaned tree: one element per line, one space of indentation per level, no XML declaration.
 * Compact enough for prompts while keeping the hierarchy readable and snapshot diffs stable.
 */
final class CleanXmlWriter {

    String write(CleanNode root) {
        StringBuilder out = new StringBuilder();
        write(root, 0, out);
        return out.toString();
    }

    private void write(CleanNode node, int depth, StringBuilder out) {
        out.repeat(' ', depth).append('<').append(node.tag());
        for (Map.Entry<String, String> attribute : node.attributes().entrySet()) {
            out.append(' ').append(attribute.getKey()).append("=\"").append(escape(attribute.getValue())).append('"');
        }
        if (node.children().isEmpty()) {
            out.append("/>\n");
            return;
        }
        out.append(">\n");
        for (CleanNode child : node.children()) {
            write(child, depth + 1, out);
        }
        out.repeat(' ', depth).append("</").append(node.tag()).append(">\n");
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                case '\n' -> escaped.append("&#10;");
                case '\r' -> escaped.append("&#13;");
                case '\t' -> escaped.append("&#9;");
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }
}
