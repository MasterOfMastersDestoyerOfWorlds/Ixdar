package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.analysis.skeleton.MeshSkeletonExtractor;
import ixdar.geometry.mesh.data.bundle.CurveGeometry;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.nodegraph.nodes.data.SkeletonNode;
import ixdar.nodegraph.runtime.NodeGraphRuntime;

/**
 * The skeleton node on a capped cylinder: its longest branch runs along the cylinder's axis, and
 * the joints along the barrel carry the cylinder's radius.
 */
class SkeletonNodeTest {

    private static final float RADIUS = 0.5f;

    private static final float HEIGHT = 3f;

    private static final String CYLINDER_GRAPH = "tube = quad_cylinder(radius=" + RADIUS
            + ", height=" + HEIGHT + ", segments=24, rings=12, cap_rings=2)\n"
            + "bones = skeleton(geometry=tube.geometry, resolution=32)\n";

    // Fraction of the height the axis branch must span, the caps' rounding allowed for.
    private static final float SPAN_FRACTION = 0.6f;

    // Relative slack on a barrel joint's radius, a few voxels of the 32-cell grid.
    private static final float RADIUS_SLACK = 0.3f;

    // A branch along the axis leans off it by less than this cosine's angle.
    private static final float ALONG_AXIS = 0.95f;

    @Test
    void theLongestBranchRunsAlongTheCylinderAndCarriesItsRadius() throws Exception {
        NodeGraphRuntime runtime = NodeGraphRuntime.fromSource(CYLINDER_GRAPH);
        GeometryBundle out = (GeometryBundle) runtime.executeGraphResult("bones",
                SkeletonNode.GEOMETRY_OUT.name);
        CurveGeometry curves = (CurveGeometry) out.slots().get(CurveGeometry.SLOT);
        int branches = curves.curveOffsets.length - 1;
        assertTrue(branches >= 1, "the cylinder gave no branch");
        float[] lengths = curves.curveAttributes.get(SkeletonNode.BRANCH_LENGTH);
        int longest = 0;
        for (int branch = 1; branch < branches; branch++) {
            longest = lengths[branch] > lengths[longest] ? branch : longest;
        }

        Vector3f low = out.mesh().boundsMin(new Vector3f());
        Vector3f extent = out.mesh().boundsMax(new Vector3f()).sub(low);
        Vector3f axis = extent.x >= extent.y && extent.x >= extent.z ? new Vector3f(1f, 0f, 0f)
                : extent.y >= extent.z ? new Vector3f(0f, 1f, 0f) : new Vector3f(0f, 0f, 1f);
        int first = curves.curveOffsets[longest];
        int last = curves.curveOffsets[longest + 1] - 1;
        Vector3f span = point(curves, last).sub(point(curves, first));
        assertTrue(span.length() > SPAN_FRACTION * HEIGHT,
                "the longest branch spans " + span.length() + " of a " + HEIGHT + " cylinder");
        assertTrue(Math.abs(span.normalize().dot(axis)) > ALONG_AXIS,
                "the longest branch leans off the axis: " + span);

        float[] radii = curves.pointAttributes.get(MeshSkeletonExtractor.RADIUS);
        assertEquals(curves.positions.length / CurveGeometry.XYZ, radii.length);
        for (int joint = first + 1; joint < last; joint++) {
            assertEquals(RADIUS, radii[joint], RADIUS_SLACK * RADIUS,
                    "barrel joint " + joint + " carries another radius than the cylinder's");
        }
    }

    private static Vector3f point(CurveGeometry curves, int point) {
        return new Vector3f(curves.positions[CurveGeometry.XYZ * point],
                curves.positions[CurveGeometry.XYZ * point + 1],
                curves.positions[CurveGeometry.XYZ * point + 2]);
    }
}
