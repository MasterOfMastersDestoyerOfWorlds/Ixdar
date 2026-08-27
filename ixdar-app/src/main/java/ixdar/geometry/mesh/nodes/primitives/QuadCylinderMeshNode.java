package ixdar.geometry.mesh.nodes.primitives;

import java.util.List;
import java.util.Map;

import ixdar.geometry.mesh.nodes.api.InputPort;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;

/**
 * All-quad cylinder primitive suitable for Catmull-Clark subdivision.
 * <p>
 * The barrel is built from quad rings (segments x rings). Each cap is made of
 * concentric quad rings converging toward the center, avoiding triangle fans
 * so the output is fully quad and subdivides cleanly.
 */
@MeshNodeAnnotation(id = "quad_cylinder")
public class QuadCylinderMeshNode implements MeshNode {
    public static final float NUM_0_5 = 0.5f;
    public static final float NUM_2_0 = 2.0f;
    public static final int NUM_3 = 3;
    public static final int NUM_8 = 8;
    public static final double NUM_2_0_2 = 2.0;
    public static final float NUM_0_05 = 0.05f;

    public static final InputPort RADIUS = new InputPort("radius", PortType.FLOAT, 0.5f, 0.001f, 100f);
    public static final InputPort HEIGHT = new InputPort("height", PortType.FLOAT, 2.0f, 0.001f, 100f);
    public static final InputPort SEGMENTS = new InputPort("segments", PortType.INT, 8, (float) 3, (float) 128);
    public static final InputPort RINGS = new InputPort("rings", PortType.INT, 1, (float) 1, (float) 64);
    public static final InputPort CAP_RINGS = new InputPort("cap_rings", PortType.INT, 2, (float) 0, (float) 16);
    public static final OutputPort MESH = new OutputPort("mesh", PortType.MESH);
    public static final OutputPort GEOMETRY = new OutputPort("geometry", PortType.GEOMETRY_BUNDLE);

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
                RADIUS.name, "Distance from the central Y-axis to the side surface. Default 0.5 (diameter 1).",
                HEIGHT.name, "Total Y-axis extent. quad_cylinder(height=h) spans y=±h/2.",
                SEGMENTS.name, "Divisions around the circumference. Higher = smoother barrel. Default 8.",
                RINGS.name, "Number of quad rings along the barrel length (between the two caps). Default 1.",
                CAP_RINGS.name, "Concentric quad rings per cap (converging toward the pole). 0 leaves the cap open; higher values give cleaner subdivision at the cap. Default 2.",
                MESH.name, "All-quad cylinder, Y-aligned, centered at origin.",
                GEOMETRY.name, "Same mesh as a GeometryBundle (slot-carrying)."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        Number rIn = ctx.getInput(RADIUS.name, Number.class);
        float radius = rIn == null ? NUM_0_5 : rIn.floatValue();

        Number hIn = ctx.getInput(HEIGHT.name, Number.class);
        float height = hIn == null ? NUM_2_0 : hIn.floatValue();

        Number sIn = ctx.getInput(SEGMENTS.name, Number.class);
        int segments = Math.max(NUM_3, sIn == null ? NUM_8 : sIn.intValue());

        Number ringsIn = ctx.getInput(RINGS.name, Number.class);
        int rings = Math.max(1, ringsIn == null ? 1 : ringsIn.intValue());

        Number capIn = ctx.getInput(CAP_RINGS.name, Number.class);
        int capRings = Math.max(1, capIn == null ? 2 : capIn.intValue());

        HalfEdgeMesh mesh = buildQuadCylinder(radius, height, segments, rings, capRings);
        mesh.computeNormals();
        ctx.setOutput(MESH.name, mesh);
        ctx.setOutput(GEOMETRY.name, GeometryBundle.ofMesh(mesh));
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
