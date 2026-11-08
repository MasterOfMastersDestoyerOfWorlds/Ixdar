package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Matcher;

import org.joml.Vector3f;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.ops.MeshVertexOffset;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.sculpt.AnchoredStroke;
import ixdar.geometry.mesh.data.sculpt.FaceAnchors;
import ixdar.geometry.mesh.data.sculpt.StrokeKey;
import ixdar.geometry.mesh.data.sculpt.StrokeSketch;
import ixdar.nodegraph.api.MapNodeContext;
import ixdar.nodegraph.field.Vector3Field;
import ixdar.nodegraph.nodes.modifier.MergeByDistanceNode;
import ixdar.nodegraph.nodes.primitives.QuadSphereMeshNode;

/**
 * The stroke key on hand-made goals over a unit sphere base: one known dent comes back as one fill
 * of about its depth at its place, one known crease as one chain along it.
 */
class StrokeKeyTest {

    private static final String TEMPLATE = "<svg xmlns=\"http://www.w3.org/2000/svg\" "
            + "width=\"5120\" height=\"2560\" viewBox=\"0 0 5120 2560\" data-center=\"0 0 0\" "
            + "data-distance=\"3.906623\" data-fov=\"45\" data-projection=\"perspective\" "
            + "data-cell-width=\"1280\" data-cell-height=\"1280\" data-columns=\"4\" "
            + "data-views=\"1.570796 0;0 0;-1.570796 0;3.141593 0;1.570796 1.570796;"
            + "1.570796 -1.570796;0.785398 0.4;2.356194 0.4\">\n</svg>\n";

    private static final float DENT_DEPTH = 0.15f;

    private static final float DENT_RADIUS = 0.4f;

    private static final float CREASE_DEPTH = 0.08f;

    private static final float CREASE_RADIUS = 0.12f;

    private static final float DEPTH_TOLERANCE = 0.4f;

    private static final Vector3f DENT_CENTRE = new Vector3f(1f, 1f, 1f).normalize();

    private static final Vector3f CREASE_START = new Vector3f(1f, 0.55f, 1.3f).normalize();

    private static final Vector3f CREASE_END = new Vector3f(1.3f, 1.25f, 0.6f).normalize();

    @Test
    @Tag("heavy")
    void oneKnownDentComesBackAsOneFillOfAboutItsDepthAtItsPlace() {
        GeometryBundle base = sphere();
        StrokeKey key = key(base, goal(base.mesh(), true), 1, 5);
        AnchoredStroke first = key.keyByCount.get(0).strokes.get(0);
        float depth = depth(first);
        Vector3f centre = new Vector3f();
        Vector3f position = new Vector3f();
        for (int point = 0; point < first.pointCount(); point++) {
            centre.add(FaceAnchors.position(base.mesh(), first.anchors, point * FaceAnchors.FIELDS, position));
        }
        centre.div(first.pointCount());
        assertTrue(first.polygon, "the dent is a fill: " + first.attributes);
        assertTrue(Math.abs(depth + DENT_DEPTH) <= DEPTH_TOLERANCE * DENT_DEPTH, "the fill sinks about the dent's depth "
                + DENT_DEPTH + ": " + depth);
        assertTrue(centre.normalize().distance(DENT_CENTRE) <= DENT_RADIUS * 0.5f, "the fill sits on the dent: "
                + centre);
        assertTrue(key.residualByCount[1] <= key.initialResidual * 0.5f, "a few strokes remove most of the dent: "
                + key.residualByCount[1] + " of " + key.initialResidual);
    }

    @Test
    @Tag("heavy")
    void oneKnownCreaseComesBackAsOneChainAlongIt() {
        GeometryBundle base = sphere();
        StrokeKey key = key(base, goal(base.mesh(), false), 1);
        AnchoredStroke first = key.keyByCount.get(0).strokes.get(0);
        assertFalse(first.polygon, "the crease is a chain: " + first.attributes);
        assertTrue(depth(first) < 0f, "the chain is a valley: " + first.attributes);
        Vector3f position = new Vector3f();
        float farthest = 0f;
        float alongLow = Float.POSITIVE_INFINITY;
        float alongHigh = Float.NEGATIVE_INFINITY;
        Vector3f direction = new Vector3f(CREASE_END).sub(CREASE_START);
        float length = direction.length();
        direction.normalize();
        for (int point = 0; point < first.pointCount(); point++) {
            FaceAnchors.position(base.mesh(), first.anchors, point * FaceAnchors.FIELDS, position);
            farthest = Math.max(farthest, creaseDistance(position));
            float along = new Vector3f(position).normalize().sub(CREASE_START).dot(direction);
            alongLow = Math.min(alongLow, along);
            alongHigh = Math.max(alongHigh, along);
        }
        assertTrue(farthest <= CREASE_RADIUS, "every point of the chain lies on the crease: " + farthest);
        assertTrue(alongHigh - alongLow >= length * 0.5f, "the chain runs along at least half the crease: "
                + (alongHigh - alongLow) + " of " + length);
    }

    private static StrokeKey key(GeometryBundle base, MeshTopology goal, int... counts) {
        StrokeKey key = new StrokeKey();
        key.symmetryAxis = -1;
        key.surfaceRounds = 1;
        key.counts = counts;
        key.refineEvery = 5;
        key.compute(base, goal, null, StrokeSketch.parse(TEMPLATE, "test template"), null);
        return key;
    }

    private static float depth(AnchoredStroke stroke) {
        Matcher depth = StrokeSketch.DEPTH_ATTRIBUTE.matcher(stroke.attributes);
        return depth.find() ? Float.parseFloat(depth.group(1)) : Float.NaN;
    }

    /**
     * The sphere pressed in: a dent at {@link #DENT_CENTRE}, or a groove along the great-circle arc
     * from {@link #CREASE_START} to {@link #CREASE_END}, both away from the bounding box's extremes
     * so the key's normalisation leaves the frame alone.
     */
    private static MeshTopology goal(MeshTopology sphere, boolean dent) {
        float[] xyz = sphere.packedPositions(null);
        float[] offset = new float[xyz.length];
        for (int vertex = 0; vertex < xyz.length; vertex += 3) {
            Vector3f position = new Vector3f(xyz[vertex], xyz[vertex + 1], xyz[vertex + 2]);
            float fraction = dent ? position.distance(DENT_CENTRE) / DENT_RADIUS : creaseDistance(position)
                    / CREASE_RADIUS;
            float press = fraction < 1f ? (dent ? DENT_DEPTH : CREASE_DEPTH) * (1f - fraction * fraction)
                    * (1f - fraction * fraction) : 0f;
            Vector3f move = new Vector3f(position).normalize().mul(-press);
            offset[vertex] = move.x;
            offset[vertex + 1] = move.y;
            offset[vertex + 2] = move.z;
        }
        return MeshVertexOffset.apply(sphere, Vector3Field.copyOf(offset));
    }

    private static float creaseDistance(Vector3f point) {
        Vector3f unit = new Vector3f(point).normalize();
        Vector3f axis = new Vector3f(CREASE_START).cross(CREASE_END).normalize();
        Vector3f onPlane = new Vector3f(unit).sub(new Vector3f(axis).mul(unit.dot(axis))).normalize();
        float span = CREASE_START.angle(CREASE_END);
        boolean between = onPlane.angle(CREASE_START) <= span && onPlane.angle(CREASE_END) <= span;
        return between ? unit.distance(onPlane) : Math.min(unit.distance(CREASE_START), unit.distance(CREASE_END));
    }

    private static GeometryBundle sphere() {
        MapNodeContext ball = new MapNodeContext(new QuadSphereMeshNode())
                .with(QuadSphereMeshNode.SIZE, 2f).with(QuadSphereMeshNode.RESOLUTION, 32).eval();
        return new MapNodeContext(new MergeByDistanceNode())
                .with(MergeByDistanceNode.GEOMETRY, ball.output(QuadSphereMeshNode.MESH, GeometryBundle.class))
                .with(MergeByDistanceNode.DISTANCE, 1e-4f).eval()
                .output(MergeByDistanceNode.GEOMETRY_OUT, GeometryBundle.class);
    }
}
