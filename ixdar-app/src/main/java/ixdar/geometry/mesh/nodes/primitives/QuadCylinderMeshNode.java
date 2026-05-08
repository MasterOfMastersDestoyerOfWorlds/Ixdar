package ixdar.geometry.mesh.nodes.primitives;

import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.HalfEdgeMesh;

/**
 * All-quad cylinder primitive suitable for Catmull-Clark subdivision.
 * <p>
 * The barrel is built from quad rings (segments x rings). Each cap is made of
 * concentric quad rings converging toward the center, avoiding triangle fans
 * so the output is fully quad and subdivides cleanly.
 */
@MeshNodeAnnotation(id = "quad_cylinder")
public class QuadCylinderMeshNode implements MeshNode {
    public static final String RADIUS_2 = "radius";
    public static final String HEIGHT_2 = "height";
    public static final String SEGMENTS_2 = "segments";
    public static final String RINGS_2 = "rings";
    public static final String CAP_RINGS_2 = "cap_rings";
    public static final String MESH_2 = "mesh";
    public static final String GEOMETRY_2 = "geometry";
    public static final float NUM_0_5 = 0.5f;
    public static final float NUM_2_0 = 2.0f;
    public static final int NUM_3 = 3;
    public static final int NUM_8 = 8;
    public static final double NUM_2_0_2 = 2.0;
    public static final float NUM_0_05 = 0.05f;

    private static final InputPort RADIUS = new InputPort(RADIUS_2, PortType.FLOAT, 0.5f, 0.001f, 100f);
    private static final InputPort HEIGHT = new InputPort(HEIGHT_2, PortType.FLOAT, 2.0f, 0.001f, 100f);
    private static final InputPort SEGMENTS = new InputPort(SEGMENTS_2, PortType.INT, 8, (float) 3, (float) 128);
    private static final InputPort RINGS = new InputPort(RINGS_2, PortType.INT, 1, (float) 1, (float) 64);
    private static final InputPort CAP_RINGS = new InputPort(CAP_RINGS_2, PortType.INT, 2, (float) 0, (float) 16);
    private static final OutputPort MESH = new OutputPort(MESH_2, PortType.MESH);
    private static final OutputPort GEOMETRY = new OutputPort(GEOMETRY_2, PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(RADIUS, HEIGHT, SEGMENTS, RINGS, CAP_RINGS);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(MESH, GEOMETRY);
    }

    @Override
    public String description() {
        return "Generates an all-quad cylinder with concentric quad-ring caps suitable for Catmull-Clark subdivision, controlled by radius, height, barrel segments/rings, and cap_rings for cap density.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                RADIUS_2, "Distance from the central Y-axis to the side surface. Default 0.5 (diameter 1).",
                HEIGHT_2, "Total Y-axis extent. quad_cylinder(height=h) spans y=±h/2.",
                SEGMENTS_2, "Divisions around the circumference. Higher = smoother barrel. Default 8.",
                RINGS_2, "Number of quad rings along the barrel length (between the two caps). Default 1.",
                CAP_RINGS_2, "Concentric quad rings per cap (converging toward the pole). 0 leaves the cap open; higher values give cleaner subdivision at the cap. Default 2.",
                MESH_2, "All-quad cylinder, Y-aligned, centered at origin.",
                GEOMETRY_2, "Same mesh as a GeometryBundle (slot-carrying)."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        Number rIn = ctx.getInput(RADIUS_2, Number.class);
        float radius = rIn == null ? NUM_0_5 : rIn.floatValue();

        Number hIn = ctx.getInput(HEIGHT_2, Number.class);
        float height = hIn == null ? NUM_2_0 : hIn.floatValue();

        Number sIn = ctx.getInput(SEGMENTS_2, Number.class);
        int segments = Math.max(NUM_3, sIn == null ? NUM_8 : sIn.intValue());

        Number ringsIn = ctx.getInput(RINGS_2, Number.class);
        int rings = Math.max(1, ringsIn == null ? 1 : ringsIn.intValue());

        Number capIn = ctx.getInput(CAP_RINGS_2, Number.class);
        int capRings = Math.max(1, capIn == null ? 2 : capIn.intValue());

        HalfEdgeMesh mesh = buildQuadCylinder(radius, height, segments, rings, capRings);
        mesh.computeNormals();
        ctx.setOutput(MESH_2, mesh);
        ctx.setOutput(GEOMETRY_2, GeometryBundle.ofMesh(mesh));
    }

    private static HalfEdgeMesh buildQuadCylinder(float radius, float height, int segments, int rings, int capRings) {
        HalfEdgeMesh mesh = new HalfEdgeMesh();
        float halfH = height * NUM_0_5;

        // Build barrel vertex rings: (rings + 1) rings of (segments) vertices
        int barrelRows = rings + 1;
        int[][] barrelVerts = new int[barrelRows][segments];

        for (int row = 0; row < barrelRows; row++) {
            float y = -halfH + height * ((float) row / rings);
            for (int seg = 0; seg < segments; seg++) {
                float angle = (float) (NUM_2_0_2 * Math.PI * seg / segments);
                float x = radius * (float) Math.cos(angle);
                float z = radius * (float) Math.sin(angle);
                barrelVerts[row][seg] = mesh.addVertex(x, y, z);
            }
        }

        // Barrel quads
        for (int row = 0; row < rings; row++) {
            for (int seg = 0; seg < segments; seg++) {
                int nextSeg = (seg + 1) % segments;
                int a = barrelVerts[row][seg];
                int b = barrelVerts[row][nextSeg];
                int c = barrelVerts[row + 1][nextSeg];
                int d = barrelVerts[row + 1][seg];
                mesh.addFace(a, b, c, d);
            }
        }

        // Build quad caps
        // Bottom cap: ring 0 (y = -halfH), facing -Y
        buildQuadCap(mesh, barrelVerts[0], segments, capRings, radius, -halfH, true);
        // Top cap: ring (barrelRows-1) (y = +halfH), facing +Y
        buildQuadCap(mesh, barrelVerts[barrelRows - 1], segments, capRings, radius, halfH, false);

        return mesh;
    }

    /**
     * Build a quad cap from the boundary ring inward.
     * Creates concentric rings of quads. The innermost ring converges to a small
     * quad grid near the center (not a single pole vertex).
     *
     * @param outerRing  vertex IDs of the outermost ring (from barrel)
     * @param segments   number of vertices around the ring
     * @param capRings   number of concentric quad rings
     * @param radius     radius of the outermost ring
     * @param y          y coordinate of the cap
     * @param flipWinding true for bottom cap (reverse winding)
     */
    private static void buildQuadCap(HalfEdgeMesh mesh, int[] outerRing, int segments,
                                      int capRings, float radius, float y, boolean flipWinding) {
        int[] prevRing = outerRing;

        for (int ring = 1; ring <= capRings; ring++) {
            float t = (float) ring / capRings;
            float r = radius * (1.0f - t);
            // Tiny offset to avoid degenerate zero-radius ring at center
            if (ring == capRings) {
                r = radius * NUM_0_05;
            }

            int[] currentRing = new int[segments];
            for (int seg = 0; seg < segments; seg++) {
                float angle = (float) (NUM_2_0_2 * Math.PI * seg / segments);
                float x = r * (float) Math.cos(angle);
                float z = r * (float) Math.sin(angle);
                currentRing[seg] = mesh.addVertex(x, y, z);
            }

            // Create quads between prevRing and currentRing
            for (int seg = 0; seg < segments; seg++) {
                int nextSeg = (seg + 1) % segments;
                if (flipWinding) {
                    mesh.addFace(prevRing[nextSeg], prevRing[seg], currentRing[seg], currentRing[nextSeg]);
                } else {
                    mesh.addFace(prevRing[seg], prevRing[nextSeg], currentRing[nextSeg], currentRing[seg]);
                }
            }
            prevRing = currentRing;
        }
    }
}
