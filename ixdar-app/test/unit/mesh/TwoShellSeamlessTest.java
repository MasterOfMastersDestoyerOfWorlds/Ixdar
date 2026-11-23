package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.fields.crossfield.CrossField;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.PackedPoints;
import ixdar.geometry.mesh.quadlayout.seamless.SeamlessUv;
import ixdar.nodegraph.api.MapNodeContext;
import ixdar.nodegraph.nodes.data.SceneModelNode;
import ixdar.nodegraph.nodes.primitives.IcosphereMeshNode;
import ixdar.nodegraph.nodes.quadlayout.QuadLayoutNode;
import ixdar.nodegraph.nodes.quadlayout.SeamlessParameterizationNode;
import ixdar.nodegraph.runtime.NodeGraphRuntime;

/**
 * CRAW-142: a surface of two closed shells, as crawfish scan IMG_4112's body and claws are, cuts
 * open into one chart per shell, each with its own translation gauge; the seamless solve pins
 * every chart and never meets a singular system.
 */
class TwoShellSeamlessTest {

    private static final int SUBDIVISIONS = 3;

    private static final float SHELL_OFFSET = 4f;

    /**
     * The two-sphere surface gets a seamless map without regularising a singular system.
     *
     * @throws Exception when a quad-layout stage fails
     */
    @Test
    void twoShellsSolveWithoutASingularSystem() throws Exception {
        MapNodeContext icosphere = new MapNodeContext(new IcosphereMeshNode())
                .with(IcosphereMeshNode.SUBDIVISIONS, SUBDIVISIONS).eval();
        HalfEdgeMesh sphere = (HalfEdgeMesh) icosphere.getOutput(IcosphereMeshNode.MESH.name, GeometryBundle.class)
                .mesh();
        int vertexCount = sphere.vertexCount();
        int faceCount = sphere.faceCount();
        PackedPoints positions = new PackedPoints(2 * vertexCount);
        int[] activeOf = new int[sphere.vertexIdBound()];
        PackedPoints spherePositions = sphere.packedPositions(activeOf);
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            positions.set(vertex, spherePositions.at(vertex));
            positions.set(vertexCount + vertex, spherePositions.get(vertex, 0) + SHELL_OFFSET,
                    spherePositions.get(vertex, 1), spherePositions.get(vertex, 2));
        }
        int[] corners = new int[2 * faceCount * 3];
        for (int activeFace = 0; activeFace < faceCount; activeFace++) {
            for (int corner = 0; corner < 3; corner++) {
                int vertex = activeOf[sphere.faceVertexAt(sphere.faceIdAt(activeFace), corner)];
                corners[activeFace * 3 + corner] = vertex;
                corners[(faceCount + activeFace) * 3 + corner] = vertexCount + vertex;
            }
        }
        HalfEdgeMesh twoShells = HalfEdgeMesh.buildFromIndexedMesh(positions, corners);
        twoShells.computeNormals();

        NodeGraphRuntime layout = new NodeGraphRuntime(QuadLayoutNode.overSceneModel());
        layout.graphInputs.put(SceneModelNode.BINDING, GeometryBundle.ofMesh(twoShells));
        CrossField crossField = (CrossField) layout.pull(QuadLayoutNode.sceneOutput(QuadLayoutNode.FIELD));
        SeamlessUv seamless = new MapNodeContext(new SeamlessParameterizationNode())
                .with(SeamlessParameterizationNode.FIELD, crossField).eval()
                .output(SeamlessParameterizationNode.UV, SeamlessUv.class);
        assertTrue(seamless.injective, "both shells are mapped injectively");
        assertNull(seamless.singularDiagnosis, "every chart's translation is pinned, so no solve is singular");
    }
}
