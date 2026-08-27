package ixdar.geometry.mesh.nodes.geometry;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Vector3f;

import ixdar.geometry.mesh.nodes.api.InputPort;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.GeometryBundles;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;

/**
 * Splits geometry into two outputs based on a per-face boolean selection.
 * <p>
 * Faces where selection=true go to the "selected" output; the rest go to
 * "inverted". Both outputs are valid standalone meshes.
 */
@MeshNodeAnnotation(id = "separate_geometry")
public class SeparateGeometryNode implements MeshNode {
    public static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    public static final InputPort SELECTION = new InputPort("selection", PortType.BOOLEAN, true);
    public static final OutputPort SELECTED = new OutputPort("selected", PortType.GEOMETRY_BUNDLE);
    public static final OutputPort INVERTED = new OutputPort("inverted", PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, SELECTION);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(SELECTED, INVERTED);
    }

    @Override
    public String description() {
        return "Splits geometry into two outputs based on a per-face boolean selection. Selected faces go to one output, the rest to the other.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                GEOMETRY.name, "Input bundle to split.",
                SELECTION.name, "Per-face BOOLEAN mask.",
                SELECTED.name, "Bundle containing only the selected faces (and their vertices).",
                INVERTED.name, "Bundle containing the non-selected faces."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle base = GeometryBundles.requireBundle(ctx.getInput(GEOMETRY.name, Object.class));
        MeshTopology in = base.mesh();
        if (in == null || in.vertexCount() == 0) {
            ctx.setOutput(SELECTED.name, GeometryBundle.empty());
            ctx.setOutput(INVERTED.name, GeometryBundle.empty());
            return;
        }

        Object selObj = FieldBroadcast.getInputOrDefault(ctx, SELECTION.name, SELECTION.defaultValue);

        int faceCount = in.faceCount();
        boolean[] faceSelected = new boolean[faceCount];
        int selCount = 0;
        for (int fi = 0; fi < faceCount; fi++) {
            boolean sel = FieldBroadcast.boolAt(selObj, fi, true);
            faceSelected[fi] = sel;
            if (sel) selCount++;
        }

        if (selCount == 0) {
            ctx.setOutput(SELECTED.name, GeometryBundle.empty());
            ctx.setOutput(INVERTED.name, base);
            return;
        }
        if (selCount == faceCount) {
            ctx.setOutput(SELECTED.name, base);
            ctx.setOutput(INVERTED.name, GeometryBundle.empty());
            return;
        }

        HalfEdgeMesh selMesh = extractFaces(in, faceSelected, true);
        HalfEdgeMesh invMesh = extractFaces(in, faceSelected, false);

        ctx.setOutput(SELECTED.name, base.withMesh(selMesh));
        ctx.setOutput(INVERTED.name, base.withMesh(invMesh));
    }

    private static HalfEdgeMesh extractFaces(MeshTopology src, boolean[] faceSelected, boolean extractSelected) {
        HalfEdgeMesh out = new HalfEdgeMesh();
        Vector3f p = new Vector3f();
        Map<Integer, Integer> vertMap = new HashMap<>();

        for (int fi = 0; fi < src.faceCount(); fi++) {
            if (faceSelected[fi] != extractSelected) continue;

            int fid = src.faceIdAt(fi);
            int fc = src.faceVertexCount(fid);
            int[] newVerts = new int[fc];

            for (int k = 0; k < fc; k++) {
                int vid = src.faceVertexAt(fid, k);
                Integer mapped = vertMap.get(vid);
                if (mapped == null) {
                    src.vertexPosition(vid, p);
                    mapped = out.addVertex(p);
                    vertMap.put(vid, mapped);
                }
                newVerts[k] = mapped;
            }
            out.addFace(newVerts);
        }

        out.computeNormals();
        return out;
    }
}
