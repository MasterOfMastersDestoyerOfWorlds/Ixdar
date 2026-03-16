package ixdar.geometry.mesh;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.MeshNode;
import ixdar.parsing.python.PythonParser;

public class NodeGraphRuntime {
    // Maps the DSL string type to a constructor
    private final Map<String, Class<? extends MeshNode>> nodeRegistry = new HashMap<>();
    
    // Stores the execution state and outputs of every node that has run
    private final Map<String, GraphNodeContext> evaluatedNodes = new HashMap<>();

    public void registerNode(String type, Class<? extends MeshNode> nodeClass) {
        nodeRegistry.put(type, nodeClass);
    }

    public HalfEdgeMesh executeGraph(List<PythonParser.ParsedNode> parsedStatements, String finalOutputId) throws Exception {
        evaluatedNodes.clear();

        for (PythonParser.ParsedNode parsedData : parsedStatements) {
            // 1. Instantiate the Node
            Class<? extends MeshNode> clazz = nodeRegistry.get(parsedData.type);
            if (clazz == null) {
                throw new IllegalArgumentException("Unknown node type: " + parsedData.type);
            }
            MeshNode activeNode = clazz.getDeclaredConstructor().newInstance();

            // 2. Prepare the Execution Context
            GraphNodeContext context = new GraphNodeContext();

            // 3. Resolve Inputs (Links and Values)
            for (Map.Entry<String, Object> arg : parsedData.arguments.entrySet()) {
                String portName = arg.getKey();
                Object rawValue = arg.getValue();

                if (rawValue instanceof PythonParser.NodeReference) {
                    // This is a Link! Pull the output from a previously evaluated node.
                    PythonParser.NodeReference ref = (PythonParser.NodeReference) rawValue;
                    GraphNodeContext sourceContext = evaluatedNodes.get(ref.nodeId);
                    
                    if (sourceContext == null) {
                        throw new RuntimeException("Node '" + ref.nodeId + "' was referenced before it was evaluated!");
                    }
                    
                    Object incomingData = sourceContext.getOutput(ref.portName);
                    context.setInputValue(portName, incomingData);
                } else {
                    // This is a static value (e.g., Number, String)
                    context.setInputValue(portName, rawValue);
                }
            }

            // 4. Evaluate the Node
            activeNode.evaluate(context);

            // 5. Store the results for the next nodes to use
            evaluatedNodes.put(parsedData.id, context);
        }

        // Return the final mesh from the designated output node
        GraphNodeContext finalContext = evaluatedNodes.get(finalOutputId);
        if (finalContext != null) {
            return (HalfEdgeMesh) finalContext.getOutput("mesh");
        }
        return null;
    }
}