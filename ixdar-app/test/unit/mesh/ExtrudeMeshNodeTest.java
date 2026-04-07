package ixdar.geometry.mesh.nodes.modifier;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.geometry.mesh.graph.dsl.DslParser;
import ixdar.geometry.mesh.graph.dsl.DslParserException;

/**
 * Unit tests for ExtrudeMeshNode.
 */
public class ExtrudeMeshNodeTest {

    @Test
    public void testExtrudeAllFaces() throws DslParserException {
        // Test extruding all faces of a cube
        String dsl = """
            base = cube(size=1.0)
            extruded = extrude_mesh(geometry=base.mesh, offset=0.2)
            """;

        var runtime = new NodeGraphRuntime();
        runtime.parseAndRun(dsl);

        MeshTopology mesh = runtime.getOutputMesh();
        assertNotNull(mesh);
        
        // Cube has 6 faces, each with 4 vertices
        // After extrusion: 6 top faces + 24 side quads = 30 faces
        // Vertices: 8 original + 24 new = 32 vertices
        assertEquals(32, mesh.vertexCount(), "Should have 32 vertices (8 original + 24 extruded)");
        assertEquals(30, mesh.faceCount(), "Should have 30 faces (6 original + 24 side quads)");
    }

    @Test
    public void testExtrudeWithOffset() throws DslParserException {
        // Test that offset parameter works correctly
        String dsl = """
            base = cube(size=1.0)
            extruded = extrude_mesh(geometry=base.mesh, offset=0.5)
            """;

        var runtime = new NodeGraphRuntime();
        runtime.parseAndRun(dsl);

        MeshTopology mesh = runtime.getOutputMesh();
        assertNotNull(mesh);
        
        // With offset=0.5, the extruded vertices should be 0.5 units away from original
        // Cube extends from -0.5 to 0.5, so extruded should extend from -1.0 to 1.0
        float[] bounds = mesh.bounds();
        assertEquals(-1.0f, bounds[0], 0.01f, "Min X should be -1.0");
        assertEquals(1.0f, bounds[3], 0.01f, "Max X should be 1.0");
    }

    @Test
    public void testExtrudeTriangleFace() throws DslParserException {
        // Test extrusion of a triangle mesh
        String dsl = """
            triangle = mesh_triangle()
            extruded = extrude_mesh(geometry=triangle.mesh, offset=0.1)
            """;

        var runtime = new NodeGraphRuntime();
        runtime.parseAndRun(dsl);

        MeshTopology mesh = runtime.getOutputMesh();
        assertNotNull(mesh);
        
        // Triangle has 1 face with 3 vertices, 3 edges
        // After extrusion: 1 top face + 3 side quads = 4 faces
        // Vertices: 3 original + 3 new = 6 vertices
        assertEquals(6, mesh.vertexCount(), "Should have 6 vertices (3 original + 3 extruded)");
        assertEquals(4, mesh.faceCount(), "Should have 4 faces (1 original + 3 side quads)");
    }

    @Test
    public void testExtrudeZeroOffset() throws DslParserException {
        // Test that zero offset returns original mesh
        String dsl = """
            base = cube(size=1.0)
            extruded = extrude_mesh(geometry=base.mesh, offset=0.0)
            """;

        var runtime = new NodeGraphRuntime();
        runtime.parseAndRun(dsl);

        MeshTopology mesh = runtime.getOutputMesh();
        assertNotNull(mesh);
        
        // With zero offset, should return original mesh unchanged
        assertEquals(8, mesh.vertexCount(), "Should have 8 vertices (original cube)");
        assertEquals(6, mesh.faceCount(), "Should have 6 faces (original cube)");
    }

    @Test
    public void testExtrudeNegativeOffset() throws DslParserException {
        // Test that negative offset extrudes inward
        String dsl = """
            base = cube(size=1.0)
            extruded = extrude_mesh(geometry=base.mesh, offset=-0.2)
            """;

        var runtime = new NodeGraphRuntime();
        runtime.parseAndRun(dsl);

        MeshTopology mesh = runtime.getOutputMesh();
        assertNotNull(mesh);
        
        // Negative offset should extrude inward
        // Cube extends from -0.5 to 0.5, so extruded should extend from -0.3 to 0.3
        float[] bounds = mesh.bounds();
        assertTrue(bounds[0] > -0.5f, "Min X should be > -0.5 (extruded inward)");
        assertTrue(bounds[3] < 0.5f, "Max X should be < 0.5 (extruded inward)");
    }
}
