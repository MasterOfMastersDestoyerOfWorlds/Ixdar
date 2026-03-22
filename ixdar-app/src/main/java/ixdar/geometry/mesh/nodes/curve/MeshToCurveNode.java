package ixdar.geometry.mesh.nodes.curve;

import java.util.ArrayList;
import java.util.List;

import org.joml.Vector3f;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.geometry.mesh.data.CurveGeometry;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.GeometryBundles;
import ixdar.geometry.mesh.data.MeshTopology;

@MeshNodeAnnotation(id = "mesh_to_curve")
public class MeshToCurveNode implements MeshNode {

    private static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    private static final OutputPort CURVE = new OutputPort("curve", PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(CURVE);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle gb = GeometryBundles.bundlePart(ctx.getInput("geometry", Object.class));
        if (gb == null) {
            ctx.setOutput("curve", GeometryBundle.empty());
            return;
        }
        MeshTopology mesh = gb.mesh();
        if (mesh == null || mesh.edgeCount() == 0) {
            ctx.setOutput("curve", gb.withSlot("_curve", CurveGeometry.singlePolyline(new float[0])));
            return;
        }

        int nSeg = mesh.edgeCount();
        ArrayList<Float> pts = new ArrayList<>(nSeg * 6);
        Vector3f a = new Vector3f();
        Vector3f b = new Vector3f();
        for (int ei = 0; ei < nSeg; ei++) {
            int eid = mesh.edgeIdAt(ei);
            int he = mesh.edgeHalfEdge(eid);
            int va = mesh.halfEdgeVertex(he);
            int vb = mesh.halfEdgeEndVertex(he);
            mesh.vertexPosition(va, a);
            mesh.vertexPosition(vb, b);
            pts.add(a.x);
            pts.add(a.y);
            pts.add(a.z);
            pts.add(b.x);
            pts.add(b.y);
            pts.add(b.z);
        }
        float[] pos = new float[pts.size()];
        for (int i = 0; i < pts.size(); i++) {
            pos[i] = pts.get(i);
        }
        int[] off = new int[nSeg + 1];
        for (int i = 0; i <= nSeg; i++) {
            off[i] = 2 * i;
        }
        CurveGeometry curve = new CurveGeometry(pos, off);
        ctx.setOutput("curve", gb.withSlot("_curve", curve));
    }
}
