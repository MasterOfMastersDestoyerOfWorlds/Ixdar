package ixdar.common.exceptions;

import java.nio.file.Path;

public class FileParseException extends Exception {
    String fileName;
    Path p;
    int lineNumber;

    /**
     * TODO: document {@code FileParseException}.
     *
     * @param p TODO: describe
     * @param name TODO: describe
     * @param lineNumber TODO: describe
     */
    public FileParseException(Path p, String name, int lineNumber) {
        this.fileName = name;
        this.p = p;
        this.lineNumber = lineNumber;
    }

    /**
     * TODO: document {@code FileParseException}.
     */
    public FileParseException() {
    }

}
