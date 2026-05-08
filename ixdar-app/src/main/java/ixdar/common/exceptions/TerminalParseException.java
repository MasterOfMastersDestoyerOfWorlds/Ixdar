package ixdar.common.exceptions;

/**
 * Thrown when a command entered at the in-app terminal cannot be parsed.
 * The supplied message is exposed verbatim via {@link #getMessage()}.
 */
public class TerminalParseException extends Exception {
    public String message;

    /**
     * Construct with a human-readable parse failure message.
     *
     * @param message description of what failed to parse
     */
    public TerminalParseException(String message) {
        this.message = message;
    }

    /**
     * Returns the message supplied at construction time.
     *
     * @return the parse failure description
     */
    @Override
    public String getMessage() {
        return message;
    }
}
