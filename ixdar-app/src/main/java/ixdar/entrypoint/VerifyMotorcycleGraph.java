package ixdar.entrypoint;

import java.io.File;
import java.util.HashMap;
import java.util.List;

import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.data.MeshLoader;
import ixdar.geometry.mesh.quadlayout.boundary.BoundaryCapper;
import ixdar.geometry.mesh.quadlayout.integergrid.SeamlessParameterization;
import ixdar.geometry.mesh.quadlayout.tmesh.MotorcycleGraph;
import ixdar.geometry.mesh.quadlayout.tmesh.TMesh;
import ixdar.geometry.mesh.quadlayout.tmesh.TNode;
import ixdar.geometry.mesh.quadlayout.vectorfield.CombedField;
import ixdar.geometry.mesh.quadlayout.vectorfield.FaceRosyField;
import ixdar.geometry.mesh.quadlayout.vectorfield.Singularity;

/**
 * CLI verifier for PATCH-41 — classical motorcycle graph + best-effort T-mesh
 * assembly on top of the seamless integer-grid parametrization (PATCH-48).
 *
 * <pre>
 *   mvn -pl ixdar-app exec:java -Dexec.mainClass=ixdar.entrypoint.VerifyMotorcycleGraph \
 *     -Dexec.args="/path/to/mesh.obj"
 * </pre>
 */
public final class VerifyMotorcycleGraph {

    private VerifyMotorcycleGraph() {}

    public static void main(String[] args) throws Exception {
        String path = args.length > 0 ? args[0] : null;
        ArrayMesh mesh;
        if (path == null) {
            System.out.println("[verify-mg] no path given; using built-in unit cube");
            mesh = makeCube();
        } else if (path.equals("--subdivided-cube")) {
            int n = args.length > 1 ? Integer.parseInt(args[1]) : 4;
            System.out.println("[verify-mg] subdivided cube n=" + n);
            mesh = makeSubdividedCube(n);
        } else {
            if (!new File(path).exists()) {
                System.err.println("[verify-mg] mesh not found: " + path);
                System.exit(2);
            }
            ArrayMesh raw = MeshLoader.load(path);
            BoundaryCapper.CapResult cap = BoundaryCapper.cap(raw);
            mesh = cap.closedMesh();
            System.out.println("Loaded " + path + "  V=" + mesh.vertexCount()
                    + "  F=" + mesh.faceCount());
        }

        FaceRosyField field = new FaceRosyField(mesh);
        field.solve();
        CombedField combed = CombedField.comb(field);
        List<Singularity> singularities = field.findSingularities();
        SeamlessParameterization param = new SeamlessParameterization(
                mesh, field, combed, singularities);

        long t0 = System.currentTimeMillis();
        MotorcycleGraph.Result graph = MotorcycleGraph.trace(
                param, mesh, field, combed, singularities);
        long tGraph = System.currentTimeMillis() - t0;

        long t1 = System.currentTimeMillis();
        TMesh tmesh = TMesh.build(graph, param);
        long tMesh = System.currentTimeMillis() - t1;

        System.out.println("Singularities      : " + singularities.size());
        System.out.println("Motorcycle traces  : " + graph.traces().size()
                + "   (in " + tGraph + " ms)");
        System.out.println("Nodes              : " + graph.nodes().size()
                + "   (sing=" + countNodes(graph.nodes(), TNode.NodeKind.SINGULARITY)
                + "  intersection=" + countNodes(graph.nodes(), TNode.NodeKind.INTERSECTION)
                + "  boundary=" + countNodes(graph.nodes(), TNode.NodeKind.BOUNDARY) + ")");
        System.out.println("T-mesh arcs        : " + tmesh.arcs().size());
        System.out.println("T-mesh patches     : " + tmesh.patches().size()
                + "   (4-cycle scan in " + tMesh + " ms)");

        // Per-direction motorcycle launch tally.
        HashMap<Integer, Integer> perDir = new HashMap<>();
        for (var m : graph.traces()) perDir.merge(m.direction(), 1, Integer::sum);
        System.out.println("Per-direction tally: " + perDir);
    }

    private static int countNodes(List<TNode> nodes, TNode.NodeKind kind) {
        int c = 0;
        for (TNode n : nodes) if (n.kind() == kind) c++;
        return c;
    }

    private static ArrayMesh makeCube() {
        float[] pos = {
                -0.5f, -0.5f, -0.5f,
                 0.5f, -0.5f, -0.5f,
                 0.5f,  0.5f, -0.5f,
                -0.5f,  0.5f, -0.5f,
                -0.5f, -0.5f,  0.5f,
                 0.5f, -0.5f,  0.5f,
                 0.5f,  0.5f,  0.5f,
                -0.5f,  0.5f,  0.5f,
        };
        int[] faces = {
                0, 2, 1,  0, 3, 2,
                4, 5, 6,  4, 6, 7,
                0, 1, 5,  0, 5, 4,
                3, 7, 6,  3, 6, 2,
                0, 4, 7,  0, 7, 3,
                1, 2, 6,  1, 6, 5,
        };
        return new ArrayMesh(pos, null, faces, 3);
    }

    private static ArrayMesh makeSubdividedCube(int n) {
        java.util.HashMap<Long, Integer> vertexOf = new java.util.HashMap<>();
        java.util.ArrayList<Float> pos = new java.util.ArrayList<>();
        java.util.ArrayList<Integer> faces = new java.util.ArrayList<>();
        for (int axis = 0; axis < 3; axis++) {
            for (int sign = -1; sign <= 1; sign += 2) {
                int[][] grid = new int[n + 1][n + 1];
                for (int j = 0; j <= n; j++) {
                    for (int i = 0; i <= n; i++) {
                        float[] xyz = new float[3];
                        xyz[axis] = sign * 0.5f;
                        int u = (axis + 1) % 3;
                        int v = (axis + 2) % 3;
                        xyz[u] = -0.5f + (float) i / n;
                        xyz[v] = -0.5f + (float) j / n;
                        long key = Math.round(xyz[0] * 1_000_000.0) * 1_000_001L * 1_000_001L
                                + Math.round(xyz[1] * 1_000_000.0) * 1_000_001L
                                + Math.round(xyz[2] * 1_000_000.0);
                        Integer existing = vertexOf.get(key);
                        int idx;
                        if (existing != null) {
                            idx = existing;
                        } else {
                            idx = pos.size() / 3;
                            pos.add(xyz[0]);
                            pos.add(xyz[1]);
                            pos.add(xyz[2]);
                            vertexOf.put(key, idx);
                        }
                        grid[j][i] = idx;
                    }
                }
                for (int j = 0; j < n; j++) {
                    for (int i = 0; i < n; i++) {
                        int a = grid[j][i];
                        int b = grid[j][i + 1];
                        int c = grid[j + 1][i + 1];
                        int d = grid[j + 1][i];
                        boolean flip = (sign > 0) ^ ((axis + 1) % 2 == 1);
                        if (flip) {
                            faces.add(a); faces.add(b); faces.add(c);
                            faces.add(a); faces.add(c); faces.add(d);
                        } else {
                            faces.add(a); faces.add(c); faces.add(b);
                            faces.add(a); faces.add(d); faces.add(c);
                        }
                    }
                }
            }
        }
        float[] posArr = new float[pos.size()];
        for (int i = 0; i < posArr.length; i++) posArr[i] = pos.get(i);
        int[] faceArr = faces.stream().mapToInt(Integer::intValue).toArray();
        return new ArrayMesh(posArr, null, faceArr, 3);
    }
}
