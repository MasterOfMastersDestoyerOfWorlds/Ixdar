package ixdar.geometry.mesh.nodes.modifier;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Vector3f;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.geometry.mesh.data.HalfEdgeMesh;
import ixdar.geometry.mesh.data.MeshTopology;

@MeshNodeAnnotation(id = "spherize")
public class SpherizeMeshNode implements MeshNode {
    private static final InputPort MESH_IN = new InputPort("mesh", PortType.MESH, null);
    private static final InputPort FACTOR = new InputPort("factor", PortType.FLOAT, 1.0f, 0f, 1f);
    private static final OutputPort MESH_OUT = new OutputPort("mesh", PortType.MESH);

    @Override
    public List<InputPort> inputs() {
        return List.of(MESH_IN, FACTOR);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(MESH_OUT);
    }

    @Override
    public String description() {
        return "Blends vertex positions toward a sphere centered at the mesh centroid, controlled by a 0-to-1 factor for partial or full spherization.";
    }

    @Override
    public void evaluate(NodeContext ctx) {
        MeshTopology inputMesh = ctx.getInput("mesh", MeshTopology.class);
        Number factorInput = ctx.getInput("factor", Number.class);

        // Clamp the factor strictly between 0.0 and 1.0
        float factor = factorInput == null ? 1.0f : Math.max(0.0f, Math.min(1.0f, factorInput.floatValue()));

        if (inputMesh == null || inputMesh.vertexCount() == 0) {
            ctx.setOutput("mesh", null);
            return;
        }

        // 1. Calculate Center and Target Radius (Average Distance)
        Vector3f center = inputMesh.center(new Vector3f());
        float totalDistance = 0f;

        for (int i = 0; i < inputMesh.vertexCount(); i++) {
            int vId = inputMesh.vertexIdAt(i);
            Vector3f pos = inputMesh.vertexPosition(vId, new Vector3f());
            totalDistance += pos.distance(center);
        }

        float targetRadius = totalDistance / inputMesh.vertexCount();

        // 2. Clone Topology and Modify Vertex Positions
        HalfEdgeMesh newMesh = new HalfEdgeMesh();
        Map<Integer, Integer> vertexMap = new HashMap<>(); // Maps Old ID -> New ID

        for (int i = 0; i < inputMesh.vertexCount(); i++) {
            int oldVId = inputMesh.vertexIdAt(i);
            Vector3f originalPos = inputMesh.vertexPosition(oldVId, new Vector3f());

            Vector3f newPos = new Vector3f(originalPos);

            if (factor > 0f) {
                // Get direction from center to vertex
                Vector3f dir = new Vector3f(originalPos).sub(center);
                float dist = dir.length();

                // Prevent division by zero if a vertex is exactly at the center
                if (dist > 0.00001f) {
                    dir.normalize();
                    // Calculate where this vertex would sit on a perfect sphere
                    Vector3f sphericalPos = new Vector3f(center).add(dir.mul(targetRadius));

                    // Linearly interpolate between the original shape and the sphere
                    newPos.lerp(sphericalPos, factor);
                }
            }

            int newVId = newMesh.addVertex(newPos);
            vertexMap.put(oldVId, newVId);
        }

        // 3. Rebuild Faces using the new Vertex IDs
        for (int i = 0; i < inputMesh.faceCount(); i++) {
            int fId = inputMesh.faceIdAt(i);
            int vCount = inputMesh.faceVertexCount(fId);
            int[] newFaceVerts = new int[vCount];

            for (int j = 0; j < vCount; j++) {
                newFaceVerts[j] = vertexMap.get(inputMesh.faceVertexAt(fId, j));
            }
            newMesh.addFace(newFaceVerts);
        }

        newMesh.computeNormals();
        ctx.setOutput("mesh", newMesh);
    }
}