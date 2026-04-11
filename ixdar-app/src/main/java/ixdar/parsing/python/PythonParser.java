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
    private int inlineCounter = 0;
    private final List<ParsedNode> pendingInlineNodes = new ArrayList<>();

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
            pendingInlineNodes.clear();
            ParsedNode node = parseStatement();
            nodes.addAll(pendingInlineNodes);
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
    // Tolerates trailing commas for LLM-generated DSL
    private void parseArguments(Map<String, Object> args) {
        parseArgument(args);
        while (current.type == TokenType.COMMA) {
            advance();
            if (current.type == TokenType.RPAREN) break; // trailing comma
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
            // Treat quoted strings same as bare identifiers for boolean/enum resolution
            // so LLMs can write mode="EQUAL" or region="true" and it still works
            String val = current.value;
            advance();
            if ("true".equalsIgnoreCase(val)) return Boolean.TRUE;
            if ("false".equalsIgnoreCase(val)) return Boolean.FALSE;
            return val;
        } else if (current.type == TokenType.IDENTIFIER) {
            String id = consume(TokenType.IDENTIFIER, "Expected identifier").value;
            if (current.type == TokenType.LPAREN) {
                return parseInlineCall(id);
            }
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

    // InlineCall -> Identifier "(" Arguments ")" ("." Identifier)?
    private NodeReference parseInlineCall(String type) {
        String syntheticId = "__inline_" + (inlineCounter++);
        consume(TokenType.LPAREN, "Expected '(' for inline call");
        ParsedNode node = new ParsedNode();
        node.id = syntheticId;
        node.type = type;
        if (current.type != TokenType.RPAREN) {
            parseArguments(node.arguments);
        }
        consume(TokenType.RPAREN, "Expected ')' to close inline call");
        pendingInlineNodes.add(node);
        if (current.type == TokenType.DOT) {
            advance();
            String port = consume(TokenType.IDENTIFIER, "Expected port name after '.'").value;
            return new NodeReference(syntheticId, port);
        }
        return new NodeReference(syntheticId, "result");
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