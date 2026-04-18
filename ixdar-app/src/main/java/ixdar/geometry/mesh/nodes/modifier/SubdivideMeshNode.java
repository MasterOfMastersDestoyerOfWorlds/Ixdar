package ixdar.geometry.mesh.nodes.modifier;

import java.util.HashMap;
import java.util.List;

import org.joml.Vector3f;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.data.ArrayMeshEngine;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.HalfEdgeMesh;
import ixdar.geometry.mesh.data.MeshTopology;

/**
 * Linear face subdivision: splits each face by inserting edge midpoints and a
 * face centroid, without moving original vertices (unlike Catmull-Clark).
 */
@MeshNodeAnnotation(id = "subdivide_mesh")
public class SubdivideMeshNode implements MeshNode {

    private static final InputPort MESH_IN = new InputPort("mesh", PortType.MESH, null);
    private static final InputPort LEVELS = new InputPort("levels", PortType.INT, 1, 0f, 6f);
    private static final OutputPort MESH_OUT = new OutputPort("mesh", PortType.MESH);
    private static final OutputPort GEOMETRY = new OutputPort("geometry", PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(MESH_IN, LEVELS);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(MESH_OUT, GEOMETRY);
    }

    @Override
    public String description() {
        return "Linearly subdivides each face by inserting edge midpoints and face centroids without smoothing, increasing mesh resolution while preserving the original shape.";
    }

    @Override
    public java.util.Map<String, String> socketDocs() {
        return java.util.Map.of(
                "mesh", "Input topology to subdivide. Each face becomes 4^levels faces.",
                "levels", "Subdivision iterations, 0..N. Each level quadruples face count. DESTRUCTIVE: consumes bezier handle slots — use BEFORE assign_bezier_handles.",
                "geometry", "Output geometry bundle wrapping the subdivided mesh (slots dropped per destructive contract)."
        );
    }

    @Override
    public boolean destructive() {
        return true;
    }

    @Override
    public List<String> consumes() {
        return List.of(
                ixdar.geometry.mesh.nodes.patch.AssignBezierHandlesNode.SLOT_HANDLES_START,
                ixdar.geometry.mesh.nodes.patch.AssignBezierHandlesNode.SLOT_HANDLES_END);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        MeshTopology mesh = ctx.getInput("mesh", MeshTopology.class);
        Number levelsInput = ctx.getInput("levels", Number.class);
        int levels = levelsInput == null ? 1 : Math.max(0, levelsInput.intValue());

        if (mesh == null) {
            ctx.setOutput("mesh", null);
            ctx.setOutput("geometry", GeometryBundle.empty());
            return;
        }

        // OOM guard: estimate output face count and cap levels to prevent downstream OOM
        int inputFaces = mesh.faceCount();
        if (inputFaces > 0 && levels > 0) {
            long estimated = inputFaces;
            int safe = 0;
            for (int i = 0; i < levels; i++) {
                estimated *= 4;
                if (estimated > 600_000) break;
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
            ctx.setOutput("mesh", mesh);
            ctx.setOutput("geometry", GeometryBundle.ofMesh(mesh));
            return;
        }

        if (ArrayMeshEngine.isUniformQuads(mesh)) {
            ArrayMesh am = mesh instanceof ArrayMesh m ? m : ArrayMeshEngine.fromUniformMeshTopology(mesh);
            for (int l = 0; l < levels; l++) {
                am = ArrayMeshEngine.subdivideQuadsOnce(am);
            }
            ctx.setOutput("mesh", am);
            ctx.setOutput("geometry", GeometryBundle.ofMesh(am));
            return;
        }

        MeshTopology current = mesh;
        for (int l = 0; l < levels; l++) {
            current = subdivideOnce(current);
        }
        ((HalfEdgeMesh) current).computeNormals();
        ctx.setOutput("mesh", current);
        ctx.setOutput("geometry", GeometryBundle.ofMesh(current));
    }

    private static HalfEdgeMesh subdivideOnce(MeshTopology src) {
        int srcV = src.vertexCount();
        int srcE = src.edgeCount();
        int srcF = src.faceCount();
        int outV = srcV + srcE + srcF;
        int outF = srcF * 4;
        int outE = srcE * 2 + srcF * 4;
        int outHE = outE * 2;

        HalfEdgeMesh out = new HalfEdgeMesh(outV, outE, outF, outHE);
        Vector3f p = new Vector3f();
        Vector3f q = new Vector3f();

        HashMap<Integer, Integer> vertMap = new HashMap<>(srcV * 4 / 3 + 1);
        for (int vi = 0; vi < src.vertexCount(); vi++) {
            int vid = src.vertexIdAt(vi);
            src.vertexPosition(vid, p);
            int nid = out.addVertex(p);
            vertMap.put(vid, nid);
        }

        HashMap<Long, Integer> edgeMidMap = new HashMap<>(srcE * 4 / 3 + 1);
        for (int ei = 0; ei < src.edgeCount(); ei++) {
            int eid = src.edgeIdAt(ei);
            int he = src.edgeHalfEdge(eid);
            int va = src.halfEdgeVertex(he);
            int vb = src.halfEdgeEndVertex(he);
            src.vertexPosition(va, p);
            src.vertexPosition(vb, q);
            p.add(q).mul(0.5f);
            int mid = out.addVertex(p);
            long key = edgeKey(va, vb);
            edgeMidMap.put(key, mid);
        }

        for (int fi = 0; fi < src.faceCount(); fi++) {
            int fid = src.faceIdAt(fi);
            int fc = src.faceVertexCount(fid);
            p.set(0f, 0f, 0f);
            int[] faceVerts = new int[fc];
            for (int k = 0; k < fc; k++) {
                faceVerts[k] = src.faceVertexAt(fid, k);
                src.vertexPosition(faceVerts[k], q);
                p.add(q);
            }
            p.mul(1f / fc);
            int centroid = out.addVertex(p);

            for (int k = 0; k < fc; k++) {
                int va = faceVerts[k];
                int vb = faceVerts[(k + 1) % fc];
                int vc = faceVerts[(k + fc - 1) % fc];
                int nva = vertMap.get(va);
                int midAB = edgeMidMap.get(edgeKey(va, vb));
                int midCA = edgeMidMap.get(edgeKey(vc, va));
                out.addFace(nva, midAB, centroid, midCA);
            }
        }
        return out;
    }

    private static long edgeKey(int a, int b) {
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        return ((long) lo << 32) | (hi & 0xffffffffL);
    }
}
