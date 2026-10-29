package com.project.migration.infrastructure.ast;

import com.project.migration.domain.exception.CodeModificationException;
import com.project.migration.infrastructure.fs.AtomicFiles;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Writes a set of files all-or-nothing: each file goes through a temp file and an atomic move; if any move
 * fails, files already replaced are restored to their original content.
 */
final class AtomicFileWriter {

    /**
     * @param contents new content per file
     * @param originals current content per file, used to roll back
     */
    void writeAll(Map<Path, String> contents, Map<Path, String> originals) {
        List<Path> written = new ArrayList<>();
        try {
            for (Map.Entry<Path, String> entry : contents.entrySet()) {
                replace(entry.getKey(), entry.getValue());
                written.add(entry.getKey());
            }
        } catch (IOException e) {
            List<Path> notRestored = rollback(written, originals);
            throw new CodeModificationException("Writing modified sources failed" + (notRestored.isEmpty()
                    ? "; already written files were restored" : "; could not restore " + notRestored), e);
        }
    }

    private static List<Path> rollback(List<Path> written, Map<Path, String> originals) {
        List<Path> notRestored = new ArrayList<>();
        for (Path file : written) {
            try {
                replace(file, originals.get(file));
            } catch (IOException e) {
                notRestored.add(file);
            }
        }
        return notRestored;
    }

    private static void replace(Path file, String content) throws IOException {
        AtomicFiles.writeString(file, content);
    }
}
