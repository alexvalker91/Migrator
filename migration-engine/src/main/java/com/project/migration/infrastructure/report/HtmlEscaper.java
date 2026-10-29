package com.project.migration.infrastructure.report;

/**
 * Escaping for the two places dynamic text ends up in the dashboard: HTML text/attributes and an inline script.
 */
final class HtmlEscaper {

    private HtmlEscaper() {
    }

    static String html(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /** JSON that may sit inside {@code <script>}: nothing in it can close the tag or open a comment. */
    static String jsonInScript(String json) {
        return json.replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026")
                .replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
    }
}
