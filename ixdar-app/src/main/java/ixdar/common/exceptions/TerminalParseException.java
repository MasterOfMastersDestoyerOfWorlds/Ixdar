package ixdar.common.exceptions;

public class TerminalParseException extends Exception {
    public String message;

    /**
     * TODO: document {@code TerminalParseException}.
     *
     * @param message TODO: describe
     */
    public TerminalParseException(String message) {
        this.message = message;
    }

    /**
     * TODO: document {@code getMessage}.
     *
     * @return TODO: describe
     */
    @Override
    public String getMessage() {
        return message;
    }
}
