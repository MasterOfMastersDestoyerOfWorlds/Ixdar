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
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.ArrayMeshEngine;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;

/**
 * Linear face subdivision: splits each face by inserting edge midpoints and a
 * face centroid, without moving original vertices (unlike Catmull-Clark).
 */
@MeshNodeAnnotation(id = "subdivide_mesh")
public class SubdivideMeshNode implements MeshNode {
    public static final String MESH = "mesh";
    public static final String LEVELS_2 = "levels";
    public static final String GEOMETRY_2 = "geometry";
    public static final int NUM_4 = 4;
    public static final int NUM_600_000 = 600_000;
    public static final int NUM_3 = 3;
    public static final float NUM_0_5 = 0.5f;
    public static final float NUM_0 = 0f;
    public static final float NUM_1 = 1f;
    public static final int NUM_32 = 32;
    public static final long NUM_0xffffffff = 0xffffffffL;

    private static final InputPort MESH_IN = new InputPort(MESH, PortType.MESH, null);
    private static final InputPort LEVELS = new InputPort(LEVELS_2, PortType.INT, 1, 0f, 6f);
    private static final OutputPort MESH_OUT = new OutputPort(MESH, PortType.MESH);
    private static final OutputPort GEOMETRY = new OutputPort(GEOMETRY_2, PortType.GEOMETRY_BUNDLE);

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
                MESH, "Input topology to subdivide. Each face becomes 4^levels faces.",
                LEVELS_2, "Subdivision iterations, 0..N. Each level quadruples face count. DESTRUCTIVE: consumes bezier handle slots — use BEFORE assign_bezier_handles.",
                GEOMETRY_2, "Output geometry bundle wrapping the subdivided mesh (slots dropped per destructive contract)."
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
        MeshTopology mesh = ctx.getInput(MESH, MeshTopology.class);
        Number levelsInput = ctx.getInput(LEVELS_2, Number.class);
        int levels = levelsInput == null ? 1 : Math.max(0, levelsInput.intValue());

        if (mesh == null) {
            ctx.setOutput(MESH, null);
            ctx.setOutput(GEOMETRY_2, GeometryBundle.empty());
            return;
        }

        // OOM guard: estimate output face count and cap levels to prevent downstream OOM
        int inputFaces = mesh.faceCount();
        if (inputFaces > 0 && levels > 0) {
            long estimated = inputFaces;
            int safe = 0;
            for (int i = 0; i < levels; i++) {
                estimated *= NUM_4;
                if (estimated > NUM_600_000) break;
                safe++;
            }
            if (safe < levels) {
                System.err.println("[subdivide_mesh] Capped levels from " + levels + " to " + safe
                        + " (" + inputFaces + " input faces × 4^" + levels + " = "
                        + (inputFaces * (long) Math.pow(NUM_4, levels)) + " would exceed 600k limit)");
                levels = safe;
            }
        }

        if (levels == 0) {
            ctx.setOutput(MESH, mesh);
            ctx.setOutput(GEOMETRY_2, GeometryBundle.ofMesh(mesh));
            return;
        }

        if (ArrayMeshEngine.isUniformQuads(mesh)) {
            ArrayMesh am = mesh instanceof ArrayMesh m ? m : ArrayMeshEngine.fromUniformMeshTopology(mesh);
            for (int l = 0; l < levels; l++) {
                am = ArrayMeshEngine.subdivideQuadsOnce(am);
            }
            ctx.setOutput(MESH, am);
            ctx.setOutput(GEOMETRY_2, GeometryBundle.ofMesh(am));
            return;
        }

        MeshTopology current = mesh;
        for (int l = 0; l < levels; l++) {
            current = subdivideOnce(current);
        }
        ((HalfEdgeMesh) current).computeNormals();
        ctx.setOutput(MESH, current);
        ctx.setOutput(GEOMETRY_2, GeometryBundle.ofMesh(current));
    }

    private static HalfEdgeMesh subdivideOnce(MeshTopology src) {
        int srcV = src.vertexCount();
        int srcE = src.edgeCount();
        int srcF = src.faceCount();
        int outV = srcV + srcE + srcF;
        int outF = srcF * NUM_4;
        int outE = srcE * 2 + srcF * NUM_4;
        int outHE = outE * 2;

        HalfEdgeMesh out = new HalfEdgeMesh(outV, outE, outF, outHE);
        Vector3f p = new Vector3f();
        Vector3f q = new Vector3f();

        HashMap<Integer, Integer> vertMap = new HashMap<>(srcV * NUM_4 / NUM_3 + 1);
        for (int vi = 0; vi < src.vertexCount(); vi++) {
            int vid = src.vertexIdAt(vi);
            src.vertexPosition(vid, p);
            int nid = out.addVertex(p);
            vertMap.put(vid, nid);
        }

        HashMap<Long, Integer> edgeMidMap = new HashMap<>(srcE * NUM_4 / NUM_3 + 1);
        for (int ei = 0; ei < src.edgeCount(); ei++) {
            int eid = src.edgeIdAt(ei);
            int he = src.edgeHalfEdge(eid);
            int va = src.halfEdgeVertex(he);
            int vb = src.halfEdgeEndVertex(he);
            src.vertexPosition(va, p);
            src.vertexPosition(vb, q);
            p.add(q).mul(NUM_0_5);
            int mid = out.addVertex(p);
            long key = edgeKey(va, vb);
            edgeMidMap.put(key, mid);
        }

        for (int fi = 0; fi < src.faceCount(); fi++) {
            int fid = src.faceIdAt(fi);
            int fc = src.faceVertexCount(fid);
            p.set(NUM_0, NUM_0, NUM_0);
            int[] faceVerts = new int[fc];
            for (int k = 0; k < fc; k++) {
                faceVerts[k] = src.faceVertexAt(fid, k);
                src.vertexPosition(faceVerts[k], q);
                p.add(q);
            }
            p.mul(NUM_1 / fc);
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
        return ((long) lo << NUM_32) | (hi & NUM_0xffffffff);
    }
}
