package ixdar.common.exceptions;

import java.nio.file.Path;

/**
 * Thrown when reading or parsing an on-disk file fails. Carries the offending
 * file's path, display name, and the line at which parsing failed.
 */
public class FileParseException extends Exception {
    String fileName;
    Path p;
    int lineNumber;

    /**
     * Construct with full failure context.
     *
     * @param p path of the file being parsed
     * @param name display name reported alongside the failure
     * @param lineNumber 1-based line at which parsing failed
     */
    public FileParseException(Path p, String name, int lineNumber) {
        this.fileName = name;
        this.p = p;
        this.lineNumber = lineNumber;
    }

    /**
     * No-arg constructor for callers that signal failure without per-line context.
     */
    public FileParseException() {
    }

}
