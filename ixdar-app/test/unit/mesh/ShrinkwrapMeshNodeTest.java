package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.nodegraph.api.MapNodeContext;
import ixdar.nodegraph.nodes.modifier.MergeByDistanceNode;
import ixdar.nodegraph.nodes.modifier.ShrinkwrapMeshNode;
import ixdar.nodegraph.nodes.primitives.QuadSphereMeshNode;

/** Shrinkwrap along normals: a small sphere wrapped onto a larger one lands on its surface. */
class ShrinkwrapMeshNodeTest {

    private static final float INNER_SIZE = 1f;

    private static final float OUTER_SIZE = 3f;

    private static final float KEEP = 0.1f;

    private static final float FACET_SAG_FRACTION_OF_RADIUS = 0.03f;

    private static final float ROUNDING = 1e-4f;

    @Test
    void aSphereWrappedOntoALargerOneLiesOnItAtTheKeptDistance() {
        GeometryBundle wrapped = new MapNodeContext(new ShrinkwrapMeshNode())
                .with(ShrinkwrapMeshNode.GEOMETRY, sphere(INNER_SIZE))
                .with(ShrinkwrapMeshNode.TARGET, sphere(OUTER_SIZE))
                .with(ShrinkwrapMeshNode.OFFSET, KEEP).eval()
                .output(ShrinkwrapMeshNode.GEOMETRY_OUT, GeometryBundle.class);
        MeshTopology mesh = wrapped.mesh();
        Vector3f position = new Vector3f();
        // The target is faceted: a ray meets a flat facet inside the sphere its corners lie on.
        float outerRadius = OUTER_SIZE / 2f;
        for (int activeVertex = 0; activeVertex < mesh.vertexCount(); activeVertex++) {
            float radius = mesh.vertexPosition(mesh.vertexIdAt(activeVertex), position).length() - KEEP;
            assertTrue(radius <= outerRadius + ROUNDING
                    && radius >= outerRadius * (1f - FACET_SAG_FRACTION_OF_RADIUS),
                    "vertex " + activeVertex + " is off the target: " + radius);
        }
    }

    private static GeometryBundle sphere(float size) {
        GeometryBundle ball = new MapNodeContext(new QuadSphereMeshNode())
                .with(QuadSphereMeshNode.SIZE, size).with(QuadSphereMeshNode.RESOLUTION, 16).eval()
                .output(QuadSphereMeshNode.MESH, GeometryBundle.class);
        return new MapNodeContext(new MergeByDistanceNode())
                .with(MergeByDistanceNode.GEOMETRY, ball)
                .with(MergeByDistanceNode.DISTANCE, 1e-4f).eval()
                .output(MergeByDistanceNode.GEOMETRY_OUT, GeometryBundle.class);
    }
}
