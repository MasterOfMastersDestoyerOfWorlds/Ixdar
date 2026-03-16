package ixdar.parsing.python;

public class PythonLexer {
    public enum TokenType {
        IDENTIFIER, NUMBER, EQUALS, LPAREN, RPAREN, COMMA, DOT, EOF
    }

    public static class Token {
        public final TokenType type;
        public final String value;

        public Token(TokenType type, String value) {
            this.type = type;
            this.value = value;
        }
    }

    private final String input;
    private int pos = 0;

    public PythonLexer(String input) {
        this.input = input;
    }

    public Token nextToken() {
        skipWhitespace();
        if (pos >= input.length())
            return new Token(TokenType.EOF, "");

        char c = input.charAt(pos);

        if (Character.isLetter(c) || c == '_')
            return readIdentifier();
        if (Character.isDigit(c) || c == '-')
            return readNumber();

        pos++;
        switch (c) {
        case '=':
            return new Token(TokenType.EQUALS, "=");
        case '(':
            return new Token(TokenType.LPAREN, "(");
        case ')':
            return new Token(TokenType.RPAREN, ")");
        case ',':
            return new Token(TokenType.COMMA, ",");
        case '.':
            return new Token(TokenType.DOT, ".");
        default:
            throw new RuntimeException("Unexpected character at " + (pos - 1) + ": " + c);
        }
    }

    private void skipWhitespace() {
        while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) {
            pos++;
        }
    }

    private Token readIdentifier() {
        int start = pos;
        while (pos < input.length() && (Character.isLetterOrDigit(input.charAt(pos)) || input.charAt(pos) == '_')) {
            pos++;
        }
        return new Token(TokenType.IDENTIFIER, input.substring(start, pos));
    }

    private Token readNumber() {
        int start = pos;
        while (pos < input.length()
                && (Character.isDigit(input.charAt(pos)) || input.charAt(pos) == '.' || input.charAt(pos) == '-')) {
            pos++;
        }
        return new Token(TokenType.NUMBER, input.substring(start, pos));
    }
}