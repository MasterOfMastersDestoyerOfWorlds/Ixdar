package shell.exceptions;

public class RangeParseException extends Exception {
    public String message;

    public RangeParseException(String message) {
        this.message = message;
    }
}
