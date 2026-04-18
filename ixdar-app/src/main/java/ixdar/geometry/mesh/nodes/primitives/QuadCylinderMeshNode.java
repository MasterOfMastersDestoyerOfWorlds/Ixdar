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

    private static final InputPort RADIUS = new InputPort("radius", PortType.FLOAT, 0.5f, 0.001f, 100f);
    private static final InputPort HEIGHT = new InputPort("height", PortType.FLOAT, 2.0f, 0.001f, 100f);
    private static final InputPort SEGMENTS = new InputPort("segments", PortType.INT, 8, (float) 3, (float) 128);
    private static final InputPort RINGS = new InputPort("rings", PortType.INT, 1, (float) 1, (float) 64);
    private static final InputPort CAP_RINGS = new InputPort("cap_rings", PortType.INT, 2, (float) 0, (float) 16);
    private static final OutputPort MESH = new OutputPort("mesh", PortType.MESH);
    private static final OutputPort GEOMETRY = new OutputPort("geometry", PortType.GEOMETRY_BUNDLE);

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
                "radius", "Distance from the central Y-axis to the side surface. Default 0.5 (diameter 1).",
                "height", "Total Y-axis extent. quad_cylinder(height=h) spans y=±h/2.",
                "segments", "Divisions around the circumference. Higher = smoother barrel. Default 8.",
                "rings", "Number of quad rings along the barrel length (between the two caps). Default 1.",
                "cap_rings", "Concentric quad rings per cap (converging toward the pole). 0 leaves the cap open; higher values give cleaner subdivision at the cap. Default 2.",
                "mesh", "All-quad cylinder, Y-aligned, centered at origin.",
                "geometry", "Same mesh as a GeometryBundle (slot-carrying)."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        Number rIn = ctx.getInput("radius", Number.class);
        float radius = rIn == null ? 0.5f : rIn.floatValue();

        Number hIn = ctx.getInput("height", Number.class);
        float height = hIn == null ? 2.0f : hIn.floatValue();

        Number sIn = ctx.getInput("segments", Number.class);
        int segments = Math.max(3, sIn == null ? 8 : sIn.intValue());

        Number ringsIn = ctx.getInput("rings", Number.class);
        int rings = Math.max(1, ringsIn == null ? 1 : ringsIn.intValue());

        Number capIn = ctx.getInput("cap_rings", Number.class);
        int capRings = Math.max(1, capIn == null ? 2 : capIn.intValue());

        HalfEdgeMesh mesh = buildQuadCylinder(radius, height, segments, rings, capRings);
        mesh.computeNormals();
        ctx.setOutput("mesh", mesh);
        ctx.setOutput("geometry", GeometryBundle.ofMesh(mesh));
    }

    private static HalfEdgeMesh buildQuadCylinder(float radius, float height, int segments, int rings, int capRings) {
        HalfEdgeMesh mesh = new HalfEdgeMesh();
        float halfH = height * 0.5f;

        // Build barrel vertex rings: (rings + 1) rings of (segments) vertices
        int barrelRows = rings + 1;
        int[][] barrelVerts = new int[barrelRows][segments];

        for (int row = 0; row < barrelRows; row++) {
            float y = -halfH + height * ((float) row / rings);
            for (int seg = 0; seg < segments; seg++) {
                float angle = (float) (2.0 * Math.PI * seg / segments);
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
                r = radius * 0.05f;
            }

            int[] currentRing = new int[segments];
            for (int seg = 0; seg < segments; seg++) {
                float angle = (float) (2.0 * Math.PI * seg / segments);
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
