package ixdar.common.exceptions;

public class TerminalParseException extends Exception {
    public String message;

    public TerminalParseException(String message) {
        this.message = message;
    }

    @Override
    public String getMessage() {
        return message;
    }
}
