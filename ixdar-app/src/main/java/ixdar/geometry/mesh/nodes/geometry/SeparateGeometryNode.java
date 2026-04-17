package ixdar.geometry.mesh.nodes.geometry;

import java.util.Arrays;
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
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.GeometryBundles;
import ixdar.geometry.mesh.data.HalfEdgeMesh;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;

/**
 * Splits geometry into two outputs based on a per-face boolean selection.
 * <p>
 * Faces where selection=true go to the "selected" output; the rest go to
 * "inverted". Both outputs are valid standalone meshes.
 */
@MeshNodeAnnotation(id = "separate_geometry")
public class SeparateGeometryNode implements MeshNode {

    private static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    private static final InputPort SELECTION = new InputPort("selection", PortType.BOOLEAN, true);
    private static final OutputPort SELECTED = new OutputPort("selected", PortType.GEOMETRY_BUNDLE);
    private static final OutputPort INVERTED = new OutputPort("inverted", PortType.GEOMETRY_BUNDLE);

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
    public void evaluate(NodeContext ctx) {
        GeometryBundle base = GeometryBundles.requireBundle(ctx.getInput("geometry", Object.class));
        MeshTopology in = base.mesh();
        if (in == null || in.vertexCount() == 0) {
            ctx.setOutput("selected", GeometryBundle.empty());
            ctx.setOutput("inverted", GeometryBundle.empty());
            return;
        }

        Object selObj = FieldBroadcast.getInputOrDefault(ctx, "selection", SELECTION.defaultValue());

        int faceCount = in.faceCount();
        boolean[] faceSelected = new boolean[faceCount];
        int selCount = 0;
        for (int fi = 0; fi < faceCount; fi++) {
            boolean sel = FieldBroadcast.boolAt(selObj, fi, true);
            faceSelected[fi] = sel;
            if (sel) selCount++;
        }

        if (selCount == 0) {
            ctx.setOutput("selected", GeometryBundle.empty());
            ctx.setOutput("inverted", base);
            return;
        }
        if (selCount == faceCount) {
            ctx.setOutput("selected", base);
            ctx.setOutput("inverted", GeometryBundle.empty());
            return;
        }

        HalfEdgeMesh selMesh = extractFaces(in, faceSelected, true);
        HalfEdgeMesh invMesh = extractFaces(in, faceSelected, false);

        ctx.setOutput("selected", base.withMesh(selMesh));
        ctx.setOutput("inverted", base.withMesh(invMesh));
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
