package shell.exceptions;

import java.nio.file.Path;

public class FileParseException extends Exception {
    String fileName;
    Path p;
    int lineNumber;

    public FileParseException(Path p, String name, int lineNumber) {
        this.fileName = name;
        this.p = p;
        this.lineNumber = lineNumber;
    }

    public FileParseException() {
        // TODO Auto-generated constructor stub
    }

}
