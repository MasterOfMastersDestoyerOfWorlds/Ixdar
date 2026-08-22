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
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;

@MeshNodeAnnotation(id = "spherize")
public class SpherizeMeshNode implements MeshNode {
    public static final float NUM_0 = 0f;
    public static final float NUM_0_00001 = 0.00001f;
    public static final InputPort MESH_IN = new InputPort("mesh", PortType.MESH, null);
    public static final InputPort FACTOR = new InputPort("factor", PortType.FLOAT, 1.0f, 0f, 1f);
    public static final OutputPort MESH_OUT = new OutputPort(MESH_IN.name, PortType.MESH);

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
    public Map<String, String> socketDocs() {
        return Map.of(
                MESH_IN.name, "Input/output mesh. Each vertex is lerped from its position toward a point on a sphere of the bounding radius centered at the centroid.",
                FACTOR.name, "Blend amount in [0, 1]. 0 = no change; 1 = vertices fully projected onto the sphere."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        MeshTopology inputMesh = ctx.getInput(MESH_IN.name, MeshTopology.class);
        Number factorInput = ctx.getInput(FACTOR.name, Number.class);

        // Clamp the factor strictly between 0.0 and 1.0
        float factor = factorInput == null ? 1.0f : Math.max(0.0f, Math.min(1.0f, factorInput.floatValue()));

        if (inputMesh == null || inputMesh.vertexCount() == 0) {
            ctx.setOutput(MESH_IN.name, null);
            return;
        }

        // 1. Calculate Center and Target Radius (Average Distance)
        Vector3f center = inputMesh.center(new Vector3f());
        float totalDistance = NUM_0;

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

            if (factor > NUM_0) {
                // Get direction from center to vertex
                Vector3f dir = new Vector3f(originalPos).sub(center);
                float dist = dir.length();

                // Prevent division by zero if a vertex is exactly at the center
                if (dist > NUM_0_00001) {
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
        ctx.setOutput(MESH_IN.name, newMesh);
    }
}