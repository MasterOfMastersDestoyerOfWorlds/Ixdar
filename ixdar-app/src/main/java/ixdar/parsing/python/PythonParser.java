package ixdar.parsing.python;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.Vector3Value;
import ixdar.parsing.python.PythonLexer.Token;
import ixdar.parsing.python.PythonLexer.TokenType;

public class PythonParser {
    private final PythonLexer lexer;
    private Token current;

    // Temporary AST structures to hold parsed data
    public static class ParsedNode {
        public String id;
        public String type;
        public Map<String, Object> arguments = new HashMap<>();
    }

    public static class NodeReference {
        public String nodeId;
        public String portName;
        public NodeReference(String n, String p) { nodeId = n; portName = p; }
    }

    public PythonParser(PythonLexer lexer) {
        this.lexer = lexer;
        this.current = lexer.nextToken();
    }

    private void advance() {
        current = lexer.nextToken();
    }

    private Token consume(TokenType expected, String errorMessage) {
        if (current.type == expected) {
            Token t = current;
            advance();
            return t;
        }
        throw new RuntimeException("Syntax Error: " + errorMessage + ". Found '" + current.value + "'");
    }

    // Graph -> Statement* EOF
    public List<ParsedNode> parseGraph() {
        List<ParsedNode> nodes = new ArrayList<>();
        while (current.type != TokenType.EOF) {
            ParsedNode node = parseStatement();
            nodes.add(node);
        }
        return nodes;
    }

    // Statement -> Identifier "=" Identifier "(" Arguments ")"
    private ParsedNode parseStatement() {
        ParsedNode node = new ParsedNode();
        
        node.id = consume(TokenType.IDENTIFIER, "Expected variable assignment ID").value;
        consume(TokenType.EQUALS, "Expected '=' after variable ID");
        
        node.type = consume(TokenType.IDENTIFIER, "Expected node type").value;
        consume(TokenType.LPAREN, "Expected '(' after node type");
        
        if (current.type != TokenType.RPAREN) {
            parseArguments(node.arguments);
        }
        
        consume(TokenType.RPAREN, "Expected ')' to close node arguments");
        return node;
    }

    // Arguments -> Argument ("," Argument)*
    private void parseArguments(Map<String, Object> args) {
        parseArgument(args);
        while (current.type == TokenType.COMMA) {
            advance();
            parseArgument(args);
        }
    }

    // Argument -> Identifier "=" Value
    private void parseArgument(Map<String, Object> args) {
        String portName = consume(TokenType.IDENTIFIER, "Expected input port name").value;
        consume(TokenType.EQUALS, "Expected '=' after port name");
        args.put(portName, parseValue());
    }

    // Value -> Number | VectorLiteral | Reference | Identifier (string/boolean literal)
    // VectorLiteral -> "<" Number "," Number "," Number ">"
    private Object parseValue() {
        if (current.type == TokenType.NUMBER) {
            float val = Float.parseFloat(current.value);
            advance();
            return val;
        } else if (current.type == TokenType.LANGLE) {
            return parseVectorLiteral();
        } else if (current.type == TokenType.STRING) {
            String val = current.value;
            advance();
            return val;
        } else if (current.type == TokenType.IDENTIFIER) {
            String id = consume(TokenType.IDENTIFIER, "Expected identifier").value;
            if (current.type == TokenType.DOT) {
                advance();
                String port = consume(TokenType.IDENTIFIER, "Expected port name after '.'").value;
                return new NodeReference(id, port);
            }
            if ("true".equalsIgnoreCase(id)) {
                return Boolean.TRUE;
            }
            if ("false".equalsIgnoreCase(id)) {
                return Boolean.FALSE;
            }
            return id;
        }
        throw new RuntimeException("Expected Number, Vector, or Node Reference, found '" + current.value + "'");
    }

    // VectorLiteral -> "<" Number "," Number "," Number ">"
    private Vector3Value parseVectorLiteral() {
        consume(TokenType.LANGLE, "Expected '<'");
        float x = Float.parseFloat(consume(TokenType.NUMBER, "Expected X component").value);
        consume(TokenType.COMMA, "Expected ',' after X component");
        float y = Float.parseFloat(consume(TokenType.NUMBER, "Expected Y component").value);
        consume(TokenType.COMMA, "Expected ',' after Y component");
        float z = Float.parseFloat(consume(TokenType.NUMBER, "Expected Z component").value);
        consume(TokenType.RANGLE, "Expected '>' to close vector literal");
        return new Vector3Value(x, y, z);
    }
}