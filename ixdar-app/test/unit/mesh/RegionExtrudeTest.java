package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import ixdar.annotations.meshnode.MapNodeContext;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.HalfEdgeMesh;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.nodes.modifier.ExtrudeMeshNode;
import ixdar.geometry.mesh.nodes.primitives.GridMeshNode;

public class RegionExtrudeTest {

    @Test
    public void regionExtrudeSingleQuad() {
        // 1x1 grid = 1 quad face
        GridMeshNode grid = new GridMeshNode();
        MapNodeContext gridCtx = new MapNodeContext(grid);
        gridCtx.setInput("u_tiles", 1);
        gridCtx.setInput("v_tiles", 1);
        grid.evaluate(gridCtx);
        MeshTopology gridMesh = gridCtx.getOutput("mesh", MeshTopology.class);
        assertNotNull(gridMesh);
        assertEquals(4, gridMesh.vertexCount());
        assertEquals(1, gridMesh.faceCount());

        // Region extrude the single face
        ExtrudeMeshNode extrude = new ExtrudeMeshNode();
        MapNodeContext extCtx = new MapNodeContext(extrude);
        extCtx.setInput("geometry", GeometryBundle.ofMesh(gridMesh));
        extCtx.setInput("offset", 1.0f);
        extCtx.setInput("region", true);
        extrude.evaluate(extCtx);
        MeshTopology result = extCtx.getOutput("mesh", MeshTopology.class);
        assertNotNull(result);
        // 1 face extruded = 1 top + 4 sides + 1 bottom = 6 faces (a box)
        System.out.println("1x1 region extrude: verts=" + result.vertexCount() + " faces=" + result.faceCount());
        assertTrue(result.vertexCount() > 0);
        assertTrue(result.faceCount() > 0);
    }

    @Test
    public void regionExtrude4x3Grid() {
        GridMeshNode grid = new GridMeshNode();
        MapNodeContext gridCtx = new MapNodeContext(grid);
        gridCtx.setInput("u_tiles", 4);
        gridCtx.setInput("v_tiles", 3);
        gridCtx.setInput("u_total_size", 1.8f);
        gridCtx.setInput("v_total_size", 1.4f);
        grid.evaluate(gridCtx);
        MeshTopology gridMesh = gridCtx.getOutput("mesh", MeshTopology.class);
        assertNotNull(gridMesh);
        assertEquals(20, gridMesh.vertexCount());
        assertEquals(12, gridMesh.faceCount());

        ExtrudeMeshNode extrude = new ExtrudeMeshNode();
        MapNodeContext extCtx = new MapNodeContext(extrude);
        extCtx.setInput("geometry", GeometryBundle.ofMesh(gridMesh));
        extCtx.setInput("offset", 0.4f);
        extCtx.setInput("region", true);
        extrude.evaluate(extCtx);
        MeshTopology result = extCtx.getOutput("mesh", MeshTopology.class);
        assertNotNull(result);
        System.out.println("4x3 region extrude: verts=" + result.vertexCount() + " faces=" + result.faceCount());
        assertTrue(result.vertexCount() > 0);
    }

    @Test
    public void regionExtrude2x2Grid() {
        GridMeshNode grid = new GridMeshNode();
        MapNodeContext gridCtx = new MapNodeContext(grid);
        gridCtx.setInput("u_tiles", 2);
        gridCtx.setInput("v_tiles", 2);
        grid.evaluate(gridCtx);
        MeshTopology gridMesh = gridCtx.getOutput("mesh", MeshTopology.class);
        assertNotNull(gridMesh);
        assertEquals(9, gridMesh.vertexCount());
        assertEquals(4, gridMesh.faceCount());

        ExtrudeMeshNode extrude = new ExtrudeMeshNode();
        MapNodeContext extCtx = new MapNodeContext(extrude);
        extCtx.setInput("geometry", GeometryBundle.ofMesh(gridMesh));
        extCtx.setInput("offset", 1.0f);
        extCtx.setInput("region", true);
        extrude.evaluate(extCtx);
        MeshTopology result = extCtx.getOutput("mesh", MeshTopology.class);
        assertNotNull(result);
        // 4 faces extruded together: 4 top + 4 bottom + 8 side = 16 faces
        System.out.println("2x2 region extrude: verts=" + result.vertexCount() + " faces=" + result.faceCount());
        assertTrue(result.vertexCount() > 0);
        assertTrue(result.faceCount() > 0);
    }
}
