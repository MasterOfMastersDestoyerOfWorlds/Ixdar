package ixdar.geometry.mesh.nodes.transform;

import java.util.List;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.annotations.meshnode.Vector3Value;
import ixdar.geometry.mesh.data.CurveGeometry;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.GeometryBundles;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;
import ixdar.geometry.mesh.nodes.patch.AssignBezierHandlesNode;

/**
 * Transforms geometry by applying scale, rotation (Euler XYZ radians), and translation.
 * Order: Scale → Rotate → Translate (standard SRT).
 */
@MeshNodeAnnotation(id = "transform_geometry")
public class TransformGeometryNode implements MeshNode {
    public static final String GEOMETRY_2 = "geometry";
    public static final String TRANSLATION_2 = "translation";
    public static final String ROTATION_2 = "rotation";
    public static final String SCALE_2 = "scale";
    public static final String CURVE = "_curve";
    public static final float NUM_0 = 0f;
    public static final float NUM_1 = 1f;
    public static final int NUM_3 = 3;

    private static final InputPort GEOMETRY = new InputPort(GEOMETRY_2, PortType.GEOMETRY_BUNDLE, null);
    private static final InputPort TRANSLATION = new InputPort(TRANSLATION_2, PortType.VECTOR3, new Vector3Value(0f, 0f, 0f));
    private static final InputPort ROTATION = new InputPort(ROTATION_2, PortType.VECTOR3, new Vector3Value(0f, 0f, 0f));
    private static final InputPort SCALE = new InputPort(SCALE_2, PortType.VECTOR3, new Vector3Value(1f, 1f, 1f));
    private static final OutputPort GEOMETRY_OUT = new OutputPort(GEOMETRY_2, PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, TRANSLATION, ROTATION, SCALE);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY_OUT);
    }

    @Override
    public String description() {
        return "Applies translation, rotation, and scale to mesh vertices and curve geometry using standard SRT order.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                GEOMETRY_2, "Input/output geometry bundle. Vertex positions and curve control points are transformed; bezier handle slots are scaled and rotated in place.",
                TRANSLATION_2, "World-space offset added after scale+rotate. translation=<X,Y,Z> shifts every vertex by those units.",
                ROTATION_2, "Euler XYZ in RADIANS (not degrees). Applied after scale, before translate. <0,0,0> = identity.",
                SCALE_2, "Per-axis multiplier applied first. scale=<X,Y,Z> stretches extent by those factors componentwise: output extent = input extent × scale. Pass 1.0 to leave an axis untouched, not 0.5. To map a unit cube (extent 1) onto a reference with extent <Ex,Ey,Ez>, use scale=<Ex,Ey,Ez>."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle base = GeometryBundles.requireBundle(ctx.getInput(GEOMETRY_2, Object.class));
        Vector3Value trans = FieldBroadcast.vector3ValueOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, TRANSLATION_2, TRANSLATION.defaultValue()),
                new Vector3Value(NUM_0, NUM_0, NUM_0));
        Vector3Value rot = FieldBroadcast.vector3ValueOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, ROTATION_2, ROTATION.defaultValue()),
                new Vector3Value(NUM_0, NUM_0, NUM_0));
        Vector3Value sc = FieldBroadcast.vector3ValueOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, SCALE_2, SCALE.defaultValue()),
                new Vector3Value(NUM_1, NUM_1, NUM_1));

        boolean hasTranslation = trans.x() != NUM_0 || trans.y() != NUM_0 || trans.z() != NUM_0;
        boolean hasRotation = rot.x() != NUM_0 || rot.y() != NUM_0 || rot.z() != NUM_0;
        boolean hasScale = sc.x() != NUM_1 || sc.y() != NUM_1 || sc.z() != NUM_1;

        if (!hasTranslation && !hasRotation && !hasScale) {
            ctx.setOutput(GEOMETRY_2, base);
            return;
        }

        // Build SRT matrix: Translation * Rotation * Scale
        Matrix4f mat = new Matrix4f();
        mat.translation(trans.x(), trans.y(), trans.z());
        if (hasRotation) {
            Quaternionf q = new Quaternionf().rotationXYZ(rot.x(), rot.y(), rot.z());
            mat.rotate(q);
        }
        if (hasScale) {
            mat.scale(sc.x(), sc.y(), sc.z());
        }

        Vector3f tmp = new Vector3f();
        GeometryBundle result = base;

        // Transform mesh vertices if present
        MeshTopology mesh = base.mesh();
        if (mesh != null && mesh.vertexCount() > 0) {
            int n = mesh.vertexCount();
            int fc = mesh.faceCount();

            // Detect uniform face vertex count and max sparse vertex ID in one pass
            int vpf = -1;
            boolean uniform = true;
            int maxSparseId = -1;
            for (int i = 0; i < n; i++) {
                int vid = mesh.vertexIdAt(i);
                if (vid > maxSparseId) {
                    maxSparseId = vid;
                }
            }
            for (int fi = 0; fi < fc; fi++) {
                int fid = mesh.faceIdAt(fi);
                int fvc = mesh.faceVertexCount(fid);
                if (vpf == -1) {
                    vpf = fvc;
                } else if (fvc != vpf) {
                    uniform = false;
                    break;
                }
            }

            if (uniform && vpf > 0 && fc > 0) {
                // Fast path: output ArrayMesh with primitive arrays, no HashMap/boxing
                float[] newPositions = new float[n * NUM_3];
                int[] sparseToDense = new int[maxSparseId + 1];
                for (int i = 0; i < n; i++) {
                    int vid = mesh.vertexIdAt(i);
                    mesh.vertexPosition(vid, tmp);
                    mat.transformPosition(tmp);
                    newPositions[i * NUM_3] = tmp.x;
                    newPositions[i * NUM_3 + 1] = tmp.y;
                    newPositions[i * NUM_3 + 2] = tmp.z;
                    sparseToDense[vid] = i;
                }
                int[] faceIndices = new int[fc * vpf];
                for (int fi = 0; fi < fc; fi++) {
                    int fid = mesh.faceIdAt(fi);
                    int base2 = fi * vpf;
                    for (int k = 0; k < vpf; k++) {
                        faceIndices[base2 + k] = sparseToDense[mesh.faceVertexAt(fid, k)];
                    }
                }
                ArrayMesh out = new ArrayMesh(newPositions, null, faceIndices, vpf);
                out.computeNormals();
                result = result.withMesh(out);
            } else {
                // Fallback: mixed-valence faces require HalfEdgeMesh
                HalfEdgeMesh out = new HalfEdgeMesh();
                java.util.HashMap<Integer, Integer> idMap = new java.util.HashMap<>();

                for (int i = 0; i < n; i++) {
                    int vid = mesh.vertexIdAt(i);
                    mesh.vertexPosition(vid, tmp);
                    mat.transformPosition(tmp);
                    int nid = out.addVertex(tmp);
                    idMap.put(vid, nid);
                }

                for (int fi = 0; fi < fc; fi++) {
                    int fid = mesh.faceIdAt(fi);
                    int fvc = mesh.faceVertexCount(fid);
                    int[] nv = new int[fvc];
                    for (int k = 0; k < fvc; k++) {
                        int ov = mesh.faceVertexAt(fid, k);
                        nv[k] = idMap.get(ov);
                    }
                    out.addFace(nv);
                }
                out.computeNormals();
                result = result.withMesh(out);
            }
        }

        // Transform bezier handle offset vectors if present. Handles are offset
        // (direction) vectors, so translation has no effect; only rotation/scale
        // need to be applied. Previously these were pass-through, which distorted
        // curves under non-uniform scale.
        if (hasRotation || hasScale) {
            result = transformHandleSlot(result, AssignBezierHandlesNode.SLOT_HANDLES_START, mat, tmp);
            result = transformHandleSlot(result, AssignBezierHandlesNode.SLOT_HANDLES_END, mat, tmp);
        }

        // Transform curve geometry if present in slots
        Object curveObj = base.slots().get(CURVE);
        if (curveObj instanceof CurveGeometry cg) {
            float[] srcPos = cg.positions();
            float[] dstPos = new float[srcPos.length];
            for (int ci = 0; ci < srcPos.length / NUM_3; ci++) {
                tmp.set(srcPos[ci * NUM_3], srcPos[ci * NUM_3 + 1], srcPos[ci * NUM_3 + 2]);
                mat.transformPosition(tmp);
                dstPos[ci * NUM_3] = tmp.x;
                dstPos[ci * NUM_3 + 1] = tmp.y;
                dstPos[ci * NUM_3 + 2] = tmp.z;
            }
            result = result.withSlot(CURVE, new CurveGeometry(dstPos, cg.curveOffsets()));
        }

        ctx.setOutput(GEOMETRY_2, result);
    }

    /**
     * If {@code bundle} has a float[] slot named {@code slotName}, applies the
     * linear part of {@code mat} to each 3-float vector in place (on a copy) and
     * returns a new bundle with the transformed slot. Otherwise returns the
     * bundle unchanged.
     */
    private static GeometryBundle transformHandleSlot(GeometryBundle bundle, String slotName,
            Matrix4f mat, Vector3f tmp) {
        Object o = bundle.slots().get(slotName);
        if (!(o instanceof float[] src)) {
            return bundle;
        }
        float[] dst = new float[src.length];
        int n = src.length / NUM_3;
        for (int i = 0; i < n; i++) {
            int b = i * NUM_3;
            tmp.set(src[b], src[b + 1], src[b + 2]);
            mat.transformDirection(tmp);
            dst[b] = tmp.x;
            dst[b + 1] = tmp.y;
            dst[b + 2] = tmp.z;
        }
        return bundle.withSlot(slotName, dst);
    }
}
