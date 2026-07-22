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
 * Torus primitive: a closed, boundary-free genus-1 surface with a regular grid of faces and no
 * poles, caps or extraordinary vertices, so V - E + F = 0 holds for any cell decomposition over
 * it.
 *
 * <p>Set triangulate to feed the quad-layout pipeline, which requires triangles.
 */
@MeshNodeAnnotation(id = "torus")
public class TorusMeshNode implements MeshNode {

    /** Port name: distance from the origin to the centre of the tube. */
    public static final String MAJOR_RADIUS_2 = "major_radius";

    /** Port name: radius of the tube itself. */
    public static final String MINOR_RADIUS_2 = "minor_radius";

    /** Port name: face divisions the long way around. */
    public static final String MAJOR_SEGMENTS_2 = "major_segments";

    /** Port name: face divisions around the tube. */
    public static final String MINOR_SEGMENTS_2 = "minor_segments";

    /** Port name: whether to split each quad into two triangles. */
    public static final String TRIANGULATE_2 = "triangulate";

    /** Port name: the generated mesh. */
    public static final String MESH_2 = "mesh";

    /** Default distance from the origin to the tube's centre. */
    public static final float DEFAULT_MAJOR_RADIUS = 1.0f;

    /** Default radius of the tube. */
    public static final float DEFAULT_MINOR_RADIUS = 0.35f;

    /** Default face divisions the long way around. */
    public static final int DEFAULT_MAJOR_SEGMENTS = 24;

    /** Default face divisions around the tube. */
    public static final int DEFAULT_MINOR_SEGMENTS = 12;

    /** Fewest divisions that still close a ring without degenerate faces. */
    public static final int MINIMUM_SEGMENTS = 3;

    /** A full turn, for stepping the two angles. */
    public static final double FULL_TURN = 2.0 * Math.PI;

    private static final InputPort MAJOR_RADIUS =
            new InputPort(MAJOR_RADIUS_2, PortType.FLOAT, DEFAULT_MAJOR_RADIUS, 0.001f, 100f);
    private static final InputPort MINOR_RADIUS =
            new InputPort(MINOR_RADIUS_2, PortType.FLOAT, DEFAULT_MINOR_RADIUS, 0.001f, 100f);
    private static final InputPort MAJOR_SEGMENTS =
            new InputPort(MAJOR_SEGMENTS_2, PortType.INT, DEFAULT_MAJOR_SEGMENTS, (float) 3, (float) 256);
    private static final InputPort MINOR_SEGMENTS =
            new InputPort(MINOR_SEGMENTS_2, PortType.INT, DEFAULT_MINOR_SEGMENTS, (float) 3, (float) 256);
    private static final InputPort TRIANGULATE =
            new InputPort(TRIANGULATE_2, PortType.BOOLEAN, false);
    private static final OutputPort MESH = new OutputPort(MESH_2, PortType.MESH);

    @Override
    public List<InputPort> inputs() {
        return List.of(MAJOR_RADIUS, MINOR_RADIUS, MAJOR_SEGMENTS, MINOR_SEGMENTS, TRIANGULATE);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(MESH);
    }

    @Override
    public String description() {
        return "Generates a torus: a closed genus-1 surface with a fully regular face grid,"
                + " no poles and no boundary, optionally triangulated.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                MAJOR_RADIUS_2, "Distance from the origin to the centre of the tube. The torus"
                        + " spans major_radius + minor_radius on the X and Z axes.",
                MINOR_RADIUS_2, "Radius of the tube. Must be smaller than major_radius for the"
                        + " surface to stay embedded; equal or larger values self-intersect.",
                MAJOR_SEGMENTS_2, "Face divisions the long way around, about the Y axis. Default 24.",
                MINOR_SEGMENTS_2, "Face divisions around the tube. Default 12.",
                TRIANGULATE_2, "Split each quad into two triangles along a diagonal. Needed by the"
                        + " quad-layout pipeline, which consumes triangle meshes. Default false.",
                MESH_2, "Torus centred at the origin, tube encircling the Y axis. Closed, genus 1,"
                        + " so V - E + F = 0."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        Number majorRadiusInput = ctx.getInput(MAJOR_RADIUS_2, Number.class);
        float majorRadius = majorRadiusInput == null
                ? DEFAULT_MAJOR_RADIUS : majorRadiusInput.floatValue();
        Number minorRadiusInput = ctx.getInput(MINOR_RADIUS_2, Number.class);
        float minorRadius = minorRadiusInput == null
                ? DEFAULT_MINOR_RADIUS : minorRadiusInput.floatValue();
        Number majorSegmentsInput = ctx.getInput(MAJOR_SEGMENTS_2, Number.class);
        int majorSegments = Math.max(MINIMUM_SEGMENTS, majorSegmentsInput == null
                ? DEFAULT_MAJOR_SEGMENTS : majorSegmentsInput.intValue());
        Number minorSegmentsInput = ctx.getInput(MINOR_SEGMENTS_2, Number.class);
        int minorSegments = Math.max(MINIMUM_SEGMENTS, minorSegmentsInput == null
                ? DEFAULT_MINOR_SEGMENTS : minorSegmentsInput.intValue());
        Boolean triangulateInput = ctx.getInput(TRIANGULATE_2, Boolean.class);
        boolean triangulate = triangulateInput != null && triangulateInput;

        HalfEdgeMesh mesh = build(majorRadius, minorRadius, majorSegments, minorSegments,
                triangulate);
        mesh.computeNormals();
        ctx.setOutput(MESH_2, mesh);
    }

    /**
     * Builds the torus as a regular grid, wrapping in both directions so no seam, pole or
     * boundary is created. Faces step around the tube first and around the ring second; the
     * reverse order winds the normals inward.
     *
     * @param majorRadius   distance from the origin to the centre of the tube
     * @param minorRadius   radius of the tube
     * @param majorSegments face divisions the long way around
     * @param minorSegments face divisions around the tube
     * @param triangulate   whether to split each quad into two triangles
     * @return the torus mesh, without normals
     */
    private static HalfEdgeMesh build(float majorRadius, float minorRadius, int majorSegments,
            int minorSegments, boolean triangulate) {
        HalfEdgeMesh mesh = new HalfEdgeMesh();
        int[][] grid = new int[majorSegments][minorSegments];
        for (int major = 0; major < majorSegments; major++) {
            double ringAngle = FULL_TURN * major / majorSegments;
            double ringCosine = Math.cos(ringAngle);
            double ringSine = Math.sin(ringAngle);
            for (int minor = 0; minor < minorSegments; minor++) {
                double tubeAngle = FULL_TURN * minor / minorSegments;
                double distanceFromAxis = majorRadius + minorRadius * Math.cos(tubeAngle);
                grid[major][minor] = mesh.addVertex(
                        (float) (distanceFromAxis * ringCosine),
                        (float) (minorRadius * Math.sin(tubeAngle)),
                        (float) (distanceFromAxis * ringSine));
            }
        }
        for (int major = 0; major < majorSegments; major++) {
            int nextMajor = (major + 1) % majorSegments;
            for (int minor = 0; minor < minorSegments; minor++) {
                int nextMinor = (minor + 1) % minorSegments;
                int corner0 = grid[major][minor];
                int corner1 = grid[major][nextMinor];
                int corner2 = grid[nextMajor][nextMinor];
                int corner3 = grid[nextMajor][minor];
                if (triangulate) {
                    mesh.addFace(corner0, corner1, corner2);
                    mesh.addFace(corner0, corner2, corner3);
                } else {
                    mesh.addFace(corner0, corner1, corner2, corner3);
                }
            }
        }
        return mesh;
    }
}
