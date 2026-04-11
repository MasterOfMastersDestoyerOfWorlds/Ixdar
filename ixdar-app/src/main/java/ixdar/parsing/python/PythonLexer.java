package ixdar.parsing.python;

public class PythonLexer {
    public enum TokenType {
        IDENTIFIER, NUMBER, STRING, EQUALS, LPAREN, RPAREN, COMMA, DOT, LANGLE, RANGLE, EOF
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
        if (c == '"') {
            return readString();
        }

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
        case '<':
            return new Token(TokenType.LANGLE, "<");
        case '>':
            return new Token(TokenType.RANGLE, ">");
        default:
            // Skip unknown characters instead of crashing — LLMs may emit stray symbols
            return nextToken();
        }
    }

    private void skipWhitespace() {
        while (pos < input.length()) {
            char c = input.charAt(pos);
            if (Character.isWhitespace(c)) {
                pos++;
                continue;
            }
            if (c == '#') {
                while (pos < input.length() && input.charAt(pos) != '\n' && input.charAt(pos) != '\r') {
                    pos++;
                }
                continue;
            }
            break;
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

    private Token readString() {
        pos++; // opening "
        StringBuilder sb = new StringBuilder();
        while (pos < input.length()) {
            char c = input.charAt(pos);
            if (c == '"') {
                pos++;
                return new Token(TokenType.STRING, sb.toString());
            }
            if (c == '\\' && pos + 1 < input.length()) {
                pos++;
                char c2 = input.charAt(pos++);
                sb.append(switch (c2) {
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    case '\\' -> '\\';
                    case '"' -> '"';
                    default -> c2;
                });
                continue;
            }
            sb.append(c);
            pos++;
        }
        throw new RuntimeException("Unterminated string literal at " + startPosDebug());
    }

    private int startPosDebug() {
        return Math.max(0, pos - 1);
    }
}