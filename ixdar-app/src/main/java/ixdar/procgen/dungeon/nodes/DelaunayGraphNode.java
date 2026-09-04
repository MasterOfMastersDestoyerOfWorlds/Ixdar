package ixdar.procgen.dungeon.nodes;

import java.util.Objects;
import java.util.List;
import java.util.Map;

import org.joml.Vector3f;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.nodes.api.InputPort;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;
import ixdar.procgen.dungeon.algo.DelaunayTriangulation2D;
import ixdar.procgen.dungeon.algo.DelaunayTriangulation3D;
import ixdar.procgen.dungeon.algo.DungeonGrids;

@MeshNodeAnnotation(id = "delaunay_graph", scopes = { "dungeon" })
public class DelaunayGraphNode implements MeshNode {
    public static final float NUM_1e_6 = 1e-6f;

    public static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    public static final OutputPort GEOMETRY_OUT = new OutputPort(GEOMETRY.name, PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY_OUT);
    }

    @Override
    public String description() {
        return "Delaunay triangulation over a point cloud via Bowyer-Watson, emitting a mesh over "
                + "the same vertices whose wire edges ARE the graph. Coplanar input (any constant "
                + "coordinate, e.g. planar rooms at z = 0) dispatches to the planar algorithm; "
                + "otherwise the 3D tetrahedralization runs. Attribute slots (e.g. 'half_extent') "
                + "carry through. Stage 2 of the dungeon pipeline — produces the candidate edge "
                + "set the MST stage selects from.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                GEOMETRY.name, "Input/output bundle. In: point cloud of sites (typically random_rooms "
                        + "output). Out: the same vertices plus one wire edge per Delaunay edge, "
                        + "sorted by (min, max) vertex index for determinism.");
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle rooms = Objects.requireNonNullElse(ctx.getInput(GEOMETRY.name, GeometryBundle.class), GeometryBundle.empty());
        MeshTopology mesh = rooms.mesh();
        int n = mesh.vertexCount();
        double[] xs = new double[n];
        double[] ys = new double[n];
        double[] zs = new double[n];
        Vector3f p = new Vector3f();
        for (int i = 0; i < n; i++) {
            mesh.vertexPosition(mesh.vertexIdAt(i), p);
            xs[i] = p.x;
            ys[i] = p.y;
            zs[i] = p.z;
        }
        int[] pairs = triangulate(xs, ys, zs);
        ctx.setOutput(GEOMETRY.name, rooms.withMesh(DungeonGrids.edgeMesh(mesh, pairs)));
    }

    private static int[] triangulate(double[] xs, double[] ys, double[] zs) {
        if (isConstant(zs)) {
            return DelaunayTriangulation2D.triangulate(xs, ys);
        }
        if (isConstant(ys)) {
            return DelaunayTriangulation2D.triangulate(xs, zs);
        }
        if (isConstant(xs)) {
            return DelaunayTriangulation2D.triangulate(ys, zs);
        }
        return DelaunayTriangulation3D.triangulate(xs, ys, zs);
    }

    private static boolean isConstant(double[] values) {
        for (int i = 1; i < values.length; i++) {
            if (Math.abs(values[i] - values[0]) > NUM_1e_6) {
                return false;
            }
        }
        return true;
    }
}
