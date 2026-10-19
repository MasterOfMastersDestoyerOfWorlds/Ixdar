package ixdar.nodegraph.nodes.modifier;

import java.util.List;
import org.joml.Vector3f;

import ixdar.nodegraph.nodes.patch.AssignBezierHandlesNode;

import java.util.Map;

import ixdar.nodegraph.api.InputPort;
import ixdar.nodegraph.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeCategory;
import ixdar.nodegraph.api.NodeContext;
import ixdar.nodegraph.api.OutputPort;
import ixdar.nodegraph.api.PortType;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.bundle.GeometryBundles;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;

/**
 * Linear face subdivision: splits each face by inserting edge midpoints and a
 * face centroid, without moving original vertices (unlike Catmull-Clark).
 */
@MeshNodeAnnotation(id = "subdivide_mesh", category = NodeCategory.MODIFIERS)
public class SubdivideMeshNode implements MeshNode {
    public static final int MAX_FACE_COUNT = 600_000;

    public static final InputPort MESH_IN = new InputPort("mesh", PortType.GEOMETRY_BUNDLE, null);
    public static final InputPort LEVELS = new InputPort("levels", PortType.INT, 1, 0f, 6f);
    public static final OutputPort GEOMETRY = new OutputPort("geometry", PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(MESH_IN, LEVELS);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY);
    }

    @Override
    public String description() {
        return "Linearly subdivides each face by inserting edge midpoints and face centroids without smoothing, increasing mesh resolution while preserving the original shape.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                MESH_IN.name, "Input topology to subdivide. Each face becomes 4^levels faces.",
                LEVELS.name, "Subdivision iterations, 0..N. Each level quadruples face count. DESTRUCTIVE: consumes bezier handle slots — use BEFORE assign_bezier_handles.",
                GEOMETRY.name, "Output geometry bundle wrapping the subdivided mesh (slots dropped per destructive contract)."
        );
    }

    @Override
    public boolean destructive() {
        return true;
    }

    @Override
    public List<String> consumes() {
        return List.of(
                AssignBezierHandlesNode.SLOT_HANDLES_START,
                AssignBezierHandlesNode.SLOT_HANDLES_END);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        MeshTopology mesh = GeometryBundles.meshPart(ctx.getInput(MESH_IN.name, GeometryBundle.class));
        Number levelsInput = ctx.getInput(LEVELS.name, Number.class);
        int levels = levelsInput == null ? 1 : Math.max(0, levelsInput.intValue());

        if (mesh == null) {
            ctx.setOutput(GEOMETRY.name, GeometryBundle.empty());
            return;
        }

        // OOM guard: estimate output face count and cap levels to prevent downstream OOM
        int inputFaces = mesh.faceCount();
        if (inputFaces > 0 && levels > 0) {
            long estimated = inputFaces;
            int safe = 0;
            for (int i = 0; i < levels; i++) {
                estimated *= 4;
                if (estimated > MAX_FACE_COUNT) break;
                safe++;
            }
            if (safe < levels) {
                System.err.println("[subdivide_mesh] Capped levels from " + levels + " to " + safe
                        + " (" + inputFaces + " input faces × 4^" + levels + " = "
                        + (inputFaces * (long) Math.pow(4, levels)) + " would exceed 600k limit)");
                levels = safe;
            }
        }

        if (levels == 0) {
            ctx.setOutput(GEOMETRY.name, GeometryBundle.ofMesh(mesh));
            return;
        }

        HalfEdgeMesh current = subdivideOnce(mesh);
        for (int l = 1; l < levels; l++) {
            current = subdivideOnce(current);
        }
        current.computeNormals();
        ctx.setOutput(GEOMETRY.name, GeometryBundle.ofMesh(current));
    }

    /**
     * One level of linear subdivision over faces of any corner count, read in place: each n-gon
     * becomes n quads (corner, outgoing edge midpoint, centroid, incoming edge midpoint).
     *
     * @param src source mesh
     * @return the all-quad mesh: source vertices in active order, then one midpoint per active
     *         edge, then one centroid per active face; normals not computed
     */
    private static HalfEdgeMesh subdivideOnce(MeshTopology src) {
        int srcV = src.vertexCount();
        int srcE = src.edgeCount();
        int srcF = src.faceCount();
        float[] positions = new float[(srcV + srcE + srcF) * 3];
        Vector3f p = new Vector3f();
        Vector3f q = new Vector3f();

        int[] outputVertexOfId = new int[src.vertexIdBound()];
        for (int activeVertex = 0; activeVertex < srcV; activeVertex++) {
            int vertexId = src.vertexIdAt(activeVertex);
            outputVertexOfId[vertexId] = activeVertex;
            src.vertexPosition(vertexId, p);
            positions[activeVertex * 3] = p.x;
            positions[activeVertex * 3 + 1] = p.y;
            positions[activeVertex * 3 + 2] = p.z;
        }

        int[] midpointOfEdge = new int[src.edgeIdBound()];
        for (int activeEdge = 0; activeEdge < srcE; activeEdge++) {
            int edgeId = src.edgeIdAt(activeEdge);
            int he = src.edgeHalfEdge(edgeId);
            src.vertexPosition(src.halfEdgeVertex(he), p);
            src.vertexPosition(src.halfEdgeEndVertex(he), q);
            p.add(q).mul(0.5f);
            int midpoint = srcV + activeEdge;
            midpointOfEdge[edgeId] = midpoint;
            positions[midpoint * 3] = p.x;
            positions[midpoint * 3 + 1] = p.y;
            positions[midpoint * 3 + 2] = p.z;
        }

        int cornerCount = 0;
        for (int activeFace = 0; activeFace < srcF; activeFace++) {
            cornerCount += src.faceVertexCount(src.faceIdAt(activeFace));
        }
        int[] faceIndices = new int[cornerCount * 4];
        int cursor = 0;
        for (int activeFace = 0; activeFace < srcF; activeFace++) {
            int faceId = src.faceIdAt(activeFace);
            int fc = src.faceVertexCount(faceId);
            p.set(0f, 0f, 0f);
            for (int k = 0; k < fc; k++) {
                src.vertexPosition(src.faceVertexAt(faceId, k), q);
                p.add(q);
            }
            p.mul(1f / fc);
            int centroid = srcV + srcE + activeFace;
            positions[centroid * 3] = p.x;
            positions[centroid * 3 + 1] = p.y;
            positions[centroid * 3 + 2] = p.z;

            for (int k = 0; k < fc; k++) {
                int cornerVertexId = src.faceVertexAt(faceId, k);
                int nextVertexId = src.faceVertexAt(faceId, (k + 1) % fc);
                int previousVertexId = src.faceVertexAt(faceId, (k + fc - 1) % fc);
                faceIndices[cursor++] = outputVertexOfId[cornerVertexId];
                faceIndices[cursor++] = midpointOfEdge[src.edgeBetween(cornerVertexId, nextVertexId)];
                faceIndices[cursor++] = centroid;
                faceIndices[cursor++] = midpointOfEdge[src.edgeBetween(previousVertexId, cornerVertexId)];
            }
        }
        return HalfEdgeMeshEngine.bulkAllocate(positions, faceIndices, 4);
    }

}
