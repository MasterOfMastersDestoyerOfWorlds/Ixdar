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
import ixdar.geometry.mesh.HalfEdgeMesh;
import ixdar.geometry.mesh.MeshTopology;

@MeshNodeAnnotation(id = "subdivision_surface")
public class SubdivisionMeshNode implements MeshNode {
    private static final InputPort MESH_IN = new InputPort("mesh", PortType.MESH, null);
    private static final InputPort LEVELS = new InputPort("levels", PortType.INT, 1);
    private static final OutputPort MESH_OUT = new OutputPort("mesh", PortType.MESH);

    @Override
    public List<InputPort> inputs() {
        return List.of(MESH_IN, LEVELS);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(MESH_OUT);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        HalfEdgeMesh mesh = ctx.getInput("mesh", HalfEdgeMesh.class);
        Number levelsInput = ctx.getInput("levels", Number.class);
        int levels = levelsInput == null ? 1 : Math.max(0, levelsInput.intValue());

        if (mesh == null) {
            ctx.setOutput("mesh", null);
            return;
        }

        HalfEdgeMesh currentMesh = mesh;
        for (int i = 0; i < levels; i++) {
            currentMesh = applyCatmullClark(currentMesh);
        }

        ctx.setOutput("mesh", currentMesh);
    }

    private HalfEdgeMesh applyCatmullClark(HalfEdgeMesh oldMesh) {
        HalfEdgeMesh newMesh = new HalfEdgeMesh();

        // Maps to track old IDs to the new Vertex IDs created in newMesh
        Map<Integer, Integer> facePointMap = new HashMap<>();
        Map<Integer, Integer> edgePointMap = new HashMap<>();
        Map<Integer, Integer> vertexPointMap = new HashMap<>();

        // 1. Calculate Face Points (Average of all vertices in a face)
        for (int i = 0; i < oldMesh.faceCount(); i++) {
            int f = oldMesh.faceIdAt(i);
            Vector3f avg = new Vector3f();
            int vCount = oldMesh.faceVertexCount(f);
            
            for (int j = 0; j < vCount; j++) {
                int v = oldMesh.faceVertexAt(f, j);
                avg.add(oldMesh.vertexPosition(v, new Vector3f()));
            }
            avg.div(vCount);
            facePointMap.put(f, newMesh.addVertex(avg));
        }

        // 2. Calculate Edge Points
        for (int i = 0; i < oldMesh.edgeCount(); i++) {
            int e = oldMesh.edgeIdAt(i);
            int he = oldMesh.edgeHalfEdge(e);
            int twin = oldMesh.halfEdgeTwin(he);
            
            int v1 = oldMesh.halfEdgeVertex(he);
            int v2 = oldMesh.halfEdgeVertex(twin);

            Vector3f avg = new Vector3f();
            avg.add(oldMesh.vertexPosition(v1, new Vector3f()));
            avg.add(oldMesh.vertexPosition(v2, new Vector3f()));

            int f1 = oldMesh.halfEdgeFace(he);
            int f2 = oldMesh.halfEdgeFace(twin);

            if (f1 != MeshTopology.NONE && f2 != MeshTopology.NONE) {
                // Interior Edge: Average of endpoints + adjacent face points
                avg.add(newMesh.vertexPosition(facePointMap.get(f1), new Vector3f()));
                avg.add(newMesh.vertexPosition(facePointMap.get(f2), new Vector3f()));
                avg.div(4.0f);
            } else {
                // Boundary Edge: Just the midpoint
                avg.div(2.0f);
            }
            edgePointMap.put(e, newMesh.addVertex(avg));
        }

        // 3. Calculate Vertex Points
        for (int i = 0; i < oldMesh.vertexCount(); i++) {
            int v = oldMesh.vertexIdAt(i);
            Vector3f originalPos = oldMesh.vertexPosition(v, new Vector3f());

            if (oldMesh.isBoundaryVertex(v)) {
                // Simplified boundary vertex: remains in place
                vertexPointMap.put(v, newMesh.addVertex(originalPos));
                continue;
            }

            int n = oldMesh.vertexFaceCount(v);
            if (n == 0) {
                vertexPointMap.put(v, newMesh.addVertex(originalPos));
                continue;
            }

            // F = Average of face points of adjacent faces
            Vector3f F = new Vector3f(); 
            for (int j = 0; j < oldMesh.vertexFaceCount(v); j++) {
                int f = oldMesh.vertexFaceAt(v, j);
                F.add(newMesh.vertexPosition(facePointMap.get(f), new Vector3f()));
            }
            F.div(n);

            // R = Average of midpoints of adjacent edges
            Vector3f R = new Vector3f(); 
            for (int j = 0; j < oldMesh.vertexEdgeCount(v); j++) {
                int e = oldMesh.vertexEdgeAt(v, j);
                int he = oldMesh.edgeHalfEdge(e);
                int v1 = oldMesh.halfEdgeVertex(he);
                int v2 = oldMesh.halfEdgeVertex(oldMesh.halfEdgeTwin(he));
                
                Vector3f mid = new Vector3f();
                mid.add(oldMesh.vertexPosition(v1, new Vector3f()));
                mid.add(oldMesh.vertexPosition(v2, new Vector3f()));
                mid.div(2.0f);
                R.add(mid);
            }
            R.div(n);

            // Catmull-Clark Vertex Formula: (F + 2R + (n-3)V) / n
            Vector3f newPos = new Vector3f();
            newPos.add(F);
            newPos.add(new Vector3f(R).mul(2.0f));
            newPos.add(new Vector3f(originalPos).mul(n - 3));
            newPos.div(n);

            vertexPointMap.put(v, newMesh.addVertex(newPos));
        }

        // 4. Connect new topology (Generate Quads)
        for (int i = 0; i < oldMesh.faceCount(); i++) {
            int f = oldMesh.faceIdAt(i);
            int fp = facePointMap.get(f);

            int heStart = oldMesh.faceHalfEdge(f);
            int he = heStart;
            
            // Loop around the face to build quads for every vertex
            do {
                int v = oldMesh.halfEdgeVertex(he);
                int prevHe = oldMesh.halfEdgePrev(he);

                int e_out = oldMesh.halfEdgeEdge(he);
                int e_in = oldMesh.halfEdgeEdge(prevHe);

                int vp = vertexPointMap.get(v);
                int ep_out = edgePointMap.get(e_out);
                int ep_in = edgePointMap.get(e_in);

                // Add the new quad: Vertex Point -> Outgoing Edge Point -> Face Point -> Incoming Edge Point
                newMesh.addFace(vp, ep_out, fp, ep_in);

                he = oldMesh.halfEdgeNext(he);
            } while (he != heStart);
        }

        newMesh.computeNormals();
        return newMesh;
    }
}