package ixdar.geometry.mesh.nodes.primitives;

import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;

/**
 * Flat polar disk whose central vertex carries valence {@code angular_segments}, which no grid
 * interior vertex can. Guaranteed ordering: center id 0, then ring-major/angular-minor ids
 * ({@code 1 + (ring - 1) * angular_segments + angular}), angular 0 at twelve o'clock, clockwise.
 */
@MeshNodeAnnotation(id = "mesh_disk")
public class DiskMeshNode implements MeshNode {

    /** Port name: concentric vertex rings around the center. */
    public static final String RINGS_2 = "rings";

    /** Port name: vertices per ring, and the center vertex's valence. */
    public static final String ANGULAR_SEGMENTS_2 = "angular_segments";

    /** Port name: radius of the outermost ring. */
    public static final String RADIUS_2 = "radius";

    /** Port name: whether to split each annulus quad into two triangles. */
    public static final String TRIANGULATE_2 = "triangulate";

    /** Port name: the generated mesh. */
    public static final String MESH_2 = "mesh";

    /** Default concentric rings. */
    public static final int DEFAULT_RINGS = 4;

    /** Default vertices per ring. */
    public static final int DEFAULT_ANGULAR_SEGMENTS = 24;

    /** Default outer radius. */
    public static final float DEFAULT_RADIUS = 1.0f;

    /** Fewest angular divisions that still close a ring without degenerate faces. */
    public static final int MINIMUM_SEGMENTS = 3;

    /** A full turn, for stepping the angle. */
    public static final double FULL_TURN = 2.0 * Math.PI;

    private static final InputPort RINGS =
            new InputPort(RINGS_2, PortType.INT, DEFAULT_RINGS, (float) 1, (float) 256);
    private static final InputPort ANGULAR_SEGMENTS = new InputPort(ANGULAR_SEGMENTS_2,
            PortType.INT, DEFAULT_ANGULAR_SEGMENTS, (float) 3, (float) 256);
    private static final InputPort RADIUS =
            new InputPort(RADIUS_2, PortType.FLOAT, DEFAULT_RADIUS, 0.001f, 100f);
    private static final InputPort TRIANGULATE =
            new InputPort(TRIANGULATE_2, PortType.BOOLEAN, false);
    private static final OutputPort MESH = new OutputPort(MESH_2, PortType.MESH);

    @Override
    public List<InputPort> inputs() {
        return List.of(RINGS, ANGULAR_SEGMENTS, RADIUS, TRIANGULATE);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(MESH);
    }

    @Override
    public String description() {
        return "Generates a flat polar disk on the XZ plane: a central vertex of valence"
                + " angular_segments, concentric rings, a triangle fan to ring one and annulus"
                + " cells outward, optionally triangulated.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                RINGS_2, "Concentric vertex rings around the center; ring k sits at radius"
                        + " k * radius / rings. Default 4.",
                ANGULAR_SEGMENTS_2, "Vertices per ring, which is also the center vertex's"
                        + " valence. Default 24.",
                RADIUS_2, "Radius of the outermost ring. Default 1.",
                TRIANGULATE_2, "Split each annulus quad into two triangles; the center fan is"
                        + " always triangles. Needed by the quad-layout pipeline, which consumes"
                        + " triangle meshes. Default false.",
                MESH_2, "Flat disk on the XZ plane, centered at the origin, Y=0. Center vertex is"
                        + " id 0, then ring-major/angular-minor ids with angular 0 at twelve"
                        + " o'clock running clockwise. Disk topology, so V - E + F = 1."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        Number ringsInput = ctx.getInput(RINGS_2, Number.class);
        int rings = Math.max(1, ringsInput == null ? DEFAULT_RINGS : ringsInput.intValue());
        Number angularInput = ctx.getInput(ANGULAR_SEGMENTS_2, Number.class);
        int angularSegments = Math.max(MINIMUM_SEGMENTS,
                angularInput == null ? DEFAULT_ANGULAR_SEGMENTS : angularInput.intValue());
        Number radiusInput = ctx.getInput(RADIUS_2, Number.class);
        float radius = radiusInput == null ? DEFAULT_RADIUS : radiusInput.floatValue();
        Boolean triangulateInput = ctx.getInput(TRIANGULATE_2, Boolean.class);
        boolean triangulate = triangulateInput != null && triangulateInput;

        HalfEdgeMesh mesh = build(rings, angularSegments, radius, triangulate);
        mesh.computeNormals();
        ctx.setOutput(MESH_2, mesh);
    }

    /**
     * Builds the disk: center first, rings outward, then the fan and annulus faces, every face
     * wound consistently so the half-edge structure is manifold with one boundary loop.
     *
     * @param rings           concentric vertex rings
     * @param angularSegments vertices per ring
     * @param radius          radius of the outermost ring
     * @param triangulate     whether annulus quads split into two triangles
     * @return the disk mesh, without normals
     */
    private static HalfEdgeMesh build(int rings, int angularSegments, float radius,
            boolean triangulate) {
        HalfEdgeMesh mesh = new HalfEdgeMesh();
        mesh.addVertex(0f, 0f, 0f);
        for (int ring = 1; ring <= rings; ring++) {
            float ringRadius = radius * ring / rings;
            for (int angular = 0; angular < angularSegments; angular++) {
                double theta = Math.PI / 2 - FULL_TURN * angular / angularSegments;
                mesh.addVertex((float) (ringRadius * Math.cos(theta)), 0f,
                        (float) (ringRadius * Math.sin(theta)));
            }
        }
        for (int angular = 0; angular < angularSegments; angular++) {
            mesh.addFace(0, vertexId(1, angular + 1, angularSegments),
                    vertexId(1, angular, angularSegments));
        }
        for (int ring = 1; ring < rings; ring++) {
            for (int angular = 0; angular < angularSegments; angular++) {
                int innerHere = vertexId(ring, angular, angularSegments);
                int innerNext = vertexId(ring, angular + 1, angularSegments);
                int outerHere = vertexId(ring + 1, angular, angularSegments);
                int outerNext = vertexId(ring + 1, angular + 1, angularSegments);
                if (triangulate) {
                    mesh.addFace(innerHere, innerNext, outerNext);
                    mesh.addFace(innerHere, outerNext, outerHere);
                } else {
                    mesh.addFace(innerHere, innerNext, outerNext, outerHere);
                }
            }
        }
        return mesh;
    }

    /**
     * The vertex id at a polar position, per the class's ordering guarantee.
     *
     * @param ring            ring index, one or more
     * @param angular         angular index, taken modulo the ring subdivisions
     * @param angularSegments vertices per ring
     * @return the vertex id there
     */
    private static int vertexId(int ring, int angular, int angularSegments) {
        return 1 + (ring - 1) * angularSegments + (angular % angularSegments);
    }
}
