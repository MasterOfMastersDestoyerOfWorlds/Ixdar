package ixdar.parsing.python;

public class PythonLexer {

    private final String input;
    private int pos = 0;
    private int line = 1;

    /**
     * TODO: document {@code PythonLexer}.
     *
     * @param input TODO: describe
     */
    public PythonLexer(String input) {
        this.input = input;
    }

    /**
     * TODO: document {@code nextToken}.
     *
     * @return TODO: describe
     */
    public Token nextToken() {
        skipWhitespace();
        if (pos >= input.length())
            return new Token(TokenType.EOF, "", line);

        int tokenLine = line;
        char c = input.charAt(pos);

        if (Character.isLetter(c) || c == '_')
            return readIdentifier(tokenLine);
        if (c == '-') {
            if (pos + 1 < input.length() && input.charAt(pos + 1) == '>') {
                pos += 2;
                return new Token(TokenType.ARROW, "->", tokenLine);
            }
            return readNumber(tokenLine);
        }
        if (Character.isDigit(c))
            return readNumber(tokenLine);
        if (c == '"') {
            return readString(tokenLine);
        }

        pos++;
        switch (c) {
        case '=':
            return new Token(TokenType.EQUALS, "=", tokenLine);
        case '(':
            return new Token(TokenType.LPAREN, "(", tokenLine);
        case ')':
            return new Token(TokenType.RPAREN, ")", tokenLine);
        case ',':
            return new Token(TokenType.COMMA, ",", tokenLine);
        case '.':
            return new Token(TokenType.DOT, ".", tokenLine);
        case '<':
            return new Token(TokenType.LANGLE, "<", tokenLine);
        case '>':
            return new Token(TokenType.RANGLE, ">", tokenLine);
        case ':':
            return new Token(TokenType.COLON, ":", tokenLine);
        default:
            // Skip unknown characters instead of crashing — LLMs may emit stray symbols
            return nextToken();
        }
    }

    private void skipWhitespace() {
        while (pos < input.length()) {
            char c = input.charAt(pos);
            if (Character.isWhitespace(c)) {
                if (c == '\n') line++;
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

    private Token readIdentifier(int tokenLine) {
        int start = pos;
        while (pos < input.length() && (Character.isLetterOrDigit(input.charAt(pos)) || input.charAt(pos) == '_')) {
            pos++;
        }
        return new Token(TokenType.IDENTIFIER, input.substring(start, pos), tokenLine);
    }

    private Token readNumber(int tokenLine) {
        int start = pos;
        while (pos < input.length()
                && (Character.isDigit(input.charAt(pos)) || input.charAt(pos) == '.' || input.charAt(pos) == '-')) {
            pos++;
        }
        return new Token(TokenType.NUMBER, input.substring(start, pos), tokenLine);
    }

    private Token readString(int tokenLine) {
        pos++; // opening "
        StringBuilder sb = new StringBuilder();
        while (pos < input.length()) {
            char c = input.charAt(pos);
            if (c == '"') {
                pos++;
                return new Token(TokenType.STRING, sb.toString(), tokenLine);
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
        throw new RuntimeException("Line " + tokenLine + ": Unterminated string literal");
    }
    public enum TokenType {
        IDENTIFIER, NUMBER, STRING, EQUALS, LPAREN, RPAREN, COMMA, DOT, LANGLE, RANGLE,
        COLON, ARROW, EOF
    }

    public static class Token {
        public final TokenType type;
        public final String value;
        public final int line;

        /**
         * TODO: document {@code Token}.
         *
         * @param type TODO: describe
         * @param value TODO: describe
         * @param line TODO: describe
         */
        public Token(TokenType type, String value, int line) {
            this.type = type;
            this.value = value;
            this.line = line;
        }
    }

}