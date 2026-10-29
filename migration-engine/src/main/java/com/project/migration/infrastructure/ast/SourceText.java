package com.project.migration.infrastructure.ast;

import com.github.javaparser.Position;
import com.github.javaparser.ast.Node;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Original text of a source file with JavaParser position → offset mapping. Modifications are expressed as
 * {@link Edit}s at AST-derived offsets, so everything outside an edit stays byte-for-byte identical.
 */
final class SourceText {

    /** Replacement of {@code [start, end)} with {@code text}; {@code start == end} is an insertion. */
    record Edit(int start, int end, String text) {
    }

    private final String text;
    private final List<Integer> lineStarts = new ArrayList<>();
    private final String lineSeparator;

    SourceText(String text) {
        this.text = text;
        lineStarts.add(0);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                i++;
                lineStarts.add(i + 1);
            } else if (c == '\n' || c == '\r') {
                lineStarts.add(i + 1);
            }
        }
        this.lineSeparator = text.contains("\r\n") ? "\r\n" : "\n";
    }

    String text() {
        return text;
    }

    String lineSeparator() {
        return lineSeparator;
    }

    /** Offset of the first character of a node. */
    int begin(Node node) {
        return offset(node.getBegin().orElseThrow(() -> new IllegalStateException("Node without position: " + node)));
    }

    /** Offset just past the last character of a node. */
    int end(Node node) {
        return offset(node.getEnd().orElseThrow(() -> new IllegalStateException("Node without position: " + node))) + 1;
    }

    String sourceOf(Node node) {
        return text.substring(begin(node), end(node));
    }

    /** Leading whitespace of the line containing {@code offset}. */
    String indentationAt(int offset) {
        int start = lineStart(offset);
        int i = start;
        while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) {
            i++;
        }
        return text.substring(start, i);
    }

    /** Whether only whitespace precedes {@code offset} on its line. */
    boolean startsLine(int offset) {
        return text.substring(lineStart(offset), offset).isBlank();
    }

    /** Whether only whitespace follows {@code offset} up to the end of its line. */
    boolean endsLine(int offset) {
        return text.substring(offset, lineEnd(offset)).isBlank();
    }

    int lineStart(int offset) {
        int line = lineIndex(offset);
        return lineStarts.get(line);
    }

    /** Offset of the line terminator of the line containing {@code offset} (or the end of text). */
    int lineEnd(int offset) {
        int line = lineIndex(offset);
        if (line + 1 >= lineStarts.size()) {
            return text.length();
        }
        int next = lineStarts.get(line + 1);
        return text.startsWith("\r\n", next - 2) ? next - 2 : next - 1;
    }

    /** Start of the line after the one containing {@code offset} (or the end of text). */
    int nextLineStart(int offset) {
        int line = lineIndex(offset);
        return line + 1 < lineStarts.size() ? lineStarts.get(line + 1) : text.length();
    }

    /** Edit removing a node; a node alone on its line(s) takes the whole line(s) with it. */
    Edit removal(Node node) {
        int start = begin(node);
        int end = end(node);
        if (startsLine(start) && endsLine(end)) {
            return new Edit(lineStart(start), nextLineStart(end), "");
        }
        int after = end;
        while (after < text.length() && text.charAt(after) == ' ') {
            after++;
        }
        return new Edit(start, after, "");
    }

    /** Indents every line but the first of a multi-line fragment by {@code indentation}. */
    String indentContinuation(String fragment, String indentation) {
        return fragment.replace("\r\n", "\n").replace("\n", lineSeparator + indentation);
    }

    String apply(List<Edit> edits) {
        List<Edit> ordered = new ArrayList<>(edits);
        ordered.sort(Comparator.comparingInt(Edit::start).thenComparingInt(Edit::end));
        for (int i = 1; i < ordered.size(); i++) {
            if (ordered.get(i).start() < ordered.get(i - 1).end()) {
                throw new IllegalStateException("Overlapping edits: " + ordered.get(i - 1) + " and " + ordered.get(i));
            }
        }
        StringBuilder result = new StringBuilder(text);
        for (int i = ordered.size() - 1; i >= 0; i--) {
            Edit edit = ordered.get(i);
            result.replace(edit.start(), edit.end(), edit.text());
        }
        return result.toString();
    }

    private int offset(Position position) {
        return lineStarts.get(position.line - 1) + position.column - 1;
    }

    private int lineIndex(int offset) {
        int low = 0;
        int high = lineStarts.size() - 1;
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (lineStarts.get(mid) <= offset) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return low;
    }
}
