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
    private final Map<String, FunctionDef> functionDefs = new HashMap<>();

    // Temporary AST structures to hold parsed data
    public static class ParsedNode {
        public String id;
        public String type;
        public int line;
        public Map<String, Object> arguments = new HashMap<>();
    }

    public static class NodeReference {
        public String nodeId;
        public String portName;
        public NodeReference(String n, String p) { nodeId = n; portName = p; }
    }

    public static class FunctionParam {
        public String name;
        public String type;
        public FunctionParam(String name, String type) { this.name = name; this.type = type; }
    }

    public static class FunctionDef {
        public String name;
        public List<FunctionParam> params;
        public String returnType;
        public List<ParsedNode> body;
        public FunctionDef(String name, List<FunctionParam> params, String returnType, List<ParsedNode> body) {
            this.name = name;
            this.params = params;
            this.returnType = returnType;
            this.body = body;
        }
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
        throw new RuntimeException("Line " + current.line + ": " + errorMessage + ". Found '" + current.value + "'");
    }

    // Graph -> (FunctionDef | Statement)* EOF
    public List<ParsedNode> parseGraph() {
        List<ParsedNode> nodes = new ArrayList<>();
        while (current.type != TokenType.EOF) {
            if (current.type == TokenType.IDENTIFIER && "def".equals(current.value)) {
                parseFunctionDef();
            } else {
                pendingInlineNodes.clear();
                ParsedNode node = parseStatement();
                nodes.addAll(pendingInlineNodes);
                nodes.add(node);
            }
        }
        return nodes;
    }

    /** Returns function definitions parsed from the graph. */
    public Map<String, FunctionDef> functionDefs() {
        return functionDefs;
    }

    // Statement -> Identifier "=" Identifier "(" Arguments ")"
    private ParsedNode parseStatement() {
        ParsedNode node = new ParsedNode();
        node.line = current.line;
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

    // FunctionDef -> "def" Identifier "(" ParamList ")" "->" Identifier ":" Body "end"
    private void parseFunctionDef() {
        consume(TokenType.IDENTIFIER, "Expected 'def'"); // consume "def"
        String name = consume(TokenType.IDENTIFIER, "Expected function name after 'def'").value;
        consume(TokenType.LPAREN, "Expected '(' after function name");
        List<FunctionParam> params = new ArrayList<>();
        if (current.type != TokenType.RPAREN) {
            parseFunctionParams(params);
        }
        consume(TokenType.RPAREN, "Expected ')' after parameter list");
        consume(TokenType.ARROW, "Expected '->' after parameter list");
        String returnType = consume(TokenType.IDENTIFIER, "Expected return type after '->'").value;
        consume(TokenType.COLON, "Expected ':' after return type");
        List<ParsedNode> body = new ArrayList<>();
        while (current.type != TokenType.EOF
                && !(current.type == TokenType.IDENTIFIER && "end".equals(current.value))) {
            pendingInlineNodes.clear();
            ParsedNode node = parseStatement();
            body.addAll(pendingInlineNodes);
            body.add(node);
        }
        consume(TokenType.IDENTIFIER, "Expected 'end' to close function definition");
        functionDefs.put(name, new FunctionDef(name, params, returnType, body));
    }

    private void parseFunctionParams(List<FunctionParam> params) {
        params.add(parseFunctionParam());
        while (current.type == TokenType.COMMA) {
            advance();
            if (current.type == TokenType.RPAREN) break; // trailing comma
            params.add(parseFunctionParam());
        }
    }

    private FunctionParam parseFunctionParam() {
        String paramName = consume(TokenType.IDENTIFIER, "Expected parameter name").value;
        consume(TokenType.COLON, "Expected ':' after parameter name");
        String paramType = consume(TokenType.IDENTIFIER, "Expected type after ':'").value;
        return new FunctionParam(paramName, paramType);
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
        throw new RuntimeException("Line " + current.line + ": Expected Number, Vector, or Node Reference, found '" + current.value + "'");
    }

    // InlineCall -> Identifier "(" Arguments ")" ("." Identifier)?
    private NodeReference parseInlineCall(String type) {
        String syntheticId = "__inline_" + (inlineCounter++);
        int callLine = current.line;
        consume(TokenType.LPAREN, "Expected '(' for inline call");
        ParsedNode node = new ParsedNode();
        node.line = callLine;
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

    // VectorLiteral -> "<" Value "," Value "," Value ">"
    // If all values are float literals, returns Vector3Value.
    // Otherwise, desugars to a synthetic combine_xyz node and returns a NodeReference.
    private Object parseVectorLiteral() {
        int litLine = current.line;
        consume(TokenType.LANGLE, "Expected '<'");
        Object xVal = parseValue();
        consume(TokenType.COMMA, "Expected ',' after X component");
        Object yVal = parseValue();
        consume(TokenType.COMMA, "Expected ',' after Y component");
        Object zVal = parseValue();
        consume(TokenType.RANGLE, "Expected '>' to close vector literal");

        if (xVal instanceof Float && yVal instanceof Float && zVal instanceof Float) {
            return new Vector3Value((float) xVal, (float) yVal, (float) zVal);
        }

        // Desugar to synthetic combine_xyz node
        String syntheticId = "__vec_" + (inlineCounter++);
        ParsedNode node = new ParsedNode();
        node.line = litLine;
        node.id = syntheticId;
        node.type = "combine_xyz";
        node.arguments.put("x", xVal);
        node.arguments.put("y", yVal);
        node.arguments.put("z", zVal);
        pendingInlineNodes.add(node);
        return new NodeReference(syntheticId, "vector");
    }
}