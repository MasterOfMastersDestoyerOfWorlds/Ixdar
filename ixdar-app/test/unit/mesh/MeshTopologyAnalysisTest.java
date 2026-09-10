package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.ops.MeshTopologyAnalysis;
import ixdar.geometry.mesh.nodes.api.MapNodeContext;
import ixdar.geometry.mesh.nodes.api.Vector3Value;
import ixdar.geometry.mesh.nodes.geometry.JoinGeometryNode;
import ixdar.geometry.mesh.nodes.primitives.CubeMeshNode;
import ixdar.geometry.mesh.nodes.primitives.GridMeshNode;
import ixdar.geometry.mesh.nodes.primitives.TorusMeshNode;
import ixdar.geometry.mesh.nodes.transform.TransformGeometryNode;

/**
 * One fixture per topology class the {@code mesh/topology} and {@code mesh/holes} routes report,
 * each built from the mesh node primitives rather than from longhand coordinates or a file.
 */
class MeshTopologyAnalysisTest {

    /** Quads around the torus fixture's major circle. */
    private static final int TORUS_MAJOR_SEGMENTS = 8;

    /** Quads around the torus fixture's minor circle. */
    private static final int TORUS_MINOR_SEGMENTS = 6;

    /** Quad faces on a cube. */
    private static final int CUBE_FACES = 6;

    /** Edges on a cube. */
    private static final int CUBE_EDGES = 12;

    /** How far apart the two-shell fixture's cubes sit, in cube widths. */
    private static final float SHELL_SEPARATION = 10;

    /** Side of the grid fixture's single tile, and so of the seam fixture's step. */
    private static final float TILE_SIZE = 1;

    /** Faces meeting on the non-manifold fixture's shared edge. */
    private static final int FIN_COUNT = 3;

    /** Weld radius wide enough to close the fin fixture's shared edge and nothing else. */
    private static final float WELD_DISTANCE = 1e-4f;

    /** Joins that must leave both inputs' vertices untouched. */
    private static final float NO_WELD = 0;

    /** Tolerance for a perimeter or area comparison. */
    private static final double MEASURE_TOLERANCE = 1e-5;

    @Test
    void torusIsOneClosedShellWithEulerCharacteristicZero() {
        MeshTopologyAnalysis analysis = analyse(torus());

        assertEquals(1, analysis.shellCount, "the torus is one edge-connected shell");
        assertEquals(0, analysis.boundaryEdgeCount, "a torus has no boundary");
        assertEquals(0, analysis.boundaryLoopCount, "and so no boundary loop");
        assertEquals(0, analysis.eulerCharacteristic, "V - E + F is zero on a genus-one surface");
        assertArrayEquals(new int[] { 0 }, analysis.shellEulerCharacteristics,
                "the only shell carries the whole characteristic");
        assertEquals(0, analysis.nonManifoldEdgeCount, "every edge has exactly two faces");
    }

    @Test
    void twoCubesAreTwoShellsEachWithEulerCharacteristicTwo() {
        MeshTopologyAnalysis analysis = analyse(twoCubes());

        assertEquals(2, analysis.shellCount, "the cubes share no edge");
        assertArrayEquals(new int[] { CUBE_FACES, CUBE_FACES }, analysis.shellFaceCounts,
                "each shell keeps its six faces");
        assertArrayEquals(new int[] { CUBE_EDGES, CUBE_EDGES }, analysis.shellEdgeCounts,
                "and its twelve edges");
        assertArrayEquals(new int[] { 2, 2 }, analysis.shellEulerCharacteristics,
                "each shell is a sphere");
        assertEquals(0, analysis.boundaryLoopCount, "both shells are closed");
    }

    @Test
    void oneGridTileHasOneLoopOfFourEdgesWithPerimeterFourAndAreaOne() {
        MeshTopologyAnalysis analysis = analyse(gridTile(true));

        assertEquals(1, analysis.boundaryLoopCount, "the tile's rim is one loop");
        assertArrayEquals(new int[] { 4 }, analysis.loopEdgeCounts,
                "the rim runs over four edges");
        assertEquals(4, analysis.loopPerimeters[0], MEASURE_TOLERANCE,
                "four unit sides make a perimeter of four");
        assertEquals(1, analysis.loopAreas[0], MEASURE_TOLERANCE,
                "the planar rim encloses one square unit");
        assertEquals(4, analysis.boundaryEdgeCount, "every rim edge has one face");
        assertEquals(1, analysis.eulerCharacteristic, "a disc has characteristic one");
        assertArrayEquals(new int[] { 0 }, analysis.loopShells, "the loop sits on the only shell");
    }

    @Test
    void threeFinsWeldedOnOneEdgeReportThatEdgeAsNonManifold() {
        MeshTopologyAnalysis analysis = analyse(threeFinsOnOneEdge());

        assertEquals(FIN_COUNT, analysis.faceCount, "the weld keeps all three fins");
        assertEquals(1, analysis.nonManifoldEdgeCount, "the shared edge carries three faces");
        assertEquals(1, analysis.shellCount, "the three fins stay one edge-connected shell");
        assertEquals(9, analysis.boundaryEdgeCount,
                "the three unshared edges of each fin are boundary");
        assertTrue(analysis.nonManifoldBoundaryVertexCount > 0,
                "each end of the shared edge has more than one outgoing boundary edge");
        assertEquals(0, analysis.boundaryLoopCount,
                "no closed loop survives an ambiguous boundary vertex");
        assertTrue(analysis.unwalkedBoundaryEdgeCount > 0, "those edges are reported as unwalked");
    }

    @Test
    void anUnweldedGridSeamIsCountedAsDuplicatePositions() {
        MeshTopologyAnalysis analysis = analyse(unweldedGridSeam());

        assertEquals(2, analysis.duplicatePositionVertexCount,
                "the seam repeats the two vertices the tiles share");
        assertEquals(6, analysis.distinctPositionCount,
                "a weld would leave the six corners of the two-tile strip");
    }

    @Test
    void zeroToleranceSkipsTheDuplicateScan() {
        MeshTopologyAnalysis analysis = new MeshTopologyAnalysis();
        analysis.duplicateTolerance = 0;
        analysis.analyze(unweldedGridSeam());

        assertEquals(0, analysis.duplicatePositionVertexCount, "nothing is compared");
        assertEquals(analysis.vertexCount, analysis.distinctPositionCount,
                "every vertex counts as its own position, seam included");
    }

    /**
     * Runs the analysis at its default tolerance.
     *
     * @param mesh fixture to analyse
     * @return the filled analysis
     */
    private static MeshTopologyAnalysis analyse(MeshTopology mesh) {
        MeshTopologyAnalysis analysis = new MeshTopologyAnalysis();
        analysis.analyze(mesh);
        return analysis;
    }

    /**
     * A triangulated torus from the {@code torus} primitive: closed, boundary-free and genus one,
     * so its Euler characteristic is zero whatever the segment counts are.
     *
     * @return the torus mesh
     */
    private static MeshTopology torus() {
        return new MapNodeContext(new TorusMeshNode())
                .with(TorusMeshNode.MAJOR_SEGMENTS, TORUS_MAJOR_SEGMENTS)
                .with(TorusMeshNode.MINOR_SEGMENTS, TORUS_MINOR_SEGMENTS)
                .with(TorusMeshNode.TRIANGULATE, true)
                .eval()
                .output(TorusMeshNode.MESH, GeometryBundle.class)
                .mesh();
    }

    /**
     * Two unit cubes far enough apart to share neither an edge nor a position, joined without a
     * weld so the analysis has to find the two shells itself.
     *
     * @return the pair as one mesh
     */
    private static MeshTopology twoCubes() {
        GeometryBundle first = cube();
        GeometryBundle second = translated(cube(), SHELL_SEPARATION, 0, 0);
        return join(first, second, NO_WELD).mesh();
    }

    /**
     * Three unit tiles hinged around the X axis at equal angles and welded along it, the smallest
     * mesh with an edge that three faces use.
     *
     * @return the fan as one mesh
     */
    private static MeshTopology threeFinsOnOneEdge() {
        GeometryBundle fan = fin(0);
        for (int index = 1; index < FIN_COUNT; index++) {
            fan = join(fan, fin(index), WELD_DISTANCE);
        }
        return fan.mesh();
    }

    /**
     * One tile of the fan, pushed off the origin so its near edge lies on the X axis and then
     * turned about that axis into its share of the full turn.
     *
     * @param index which fin, counted from the unturned one
     * @return the fin as a geometry bundle
     */
    private static GeometryBundle fin(int index) {
        GeometryBundle hinged = translated(gridTileBundle(false), 0, 0, TILE_SIZE / 2);
        double turn = TorusMeshNode.FULL_TURN * index / FIN_COUNT;
        return new MapNodeContext(new TransformGeometryNode())
                .with(TransformGeometryNode.GEOMETRY, hinged)
                .with(TransformGeometryNode.ROTATION, new Vector3Value((float) turn, 0, 0))
                .eval()
                .output(TransformGeometryNode.GEOMETRY_OUT, GeometryBundle.class);
    }

    /**
     * Two tiles a tile-width apart and joined without a weld, so the pair of positions on their
     * shared edge is stored twice.
     *
     * @return the strip as one mesh
     */
    private static MeshTopology unweldedGridSeam() {
        GeometryBundle left = gridTileBundle(true);
        GeometryBundle right = translated(gridTileBundle(true), TILE_SIZE, 0, 0);
        return join(left, right, NO_WELD).mesh();
    }

    /**
     * A single unit tile from the {@code mesh_grid} primitive, in the y = 0 plane and centred on
     * the origin.
     *
     * @param triangulate whether to split the tile into two triangles
     * @return the tile mesh
     */
    private static MeshTopology gridTile(boolean triangulate) {
        return gridTileBundle(triangulate).mesh();
    }

    /**
     * The tile of {@link #gridTile}, still in its bundle so it can be transformed and joined.
     *
     * @param triangulate whether to split the tile into two triangles
     * @return the tile as a geometry bundle
     */
    private static GeometryBundle gridTileBundle(boolean triangulate) {
        return new MapNodeContext(new GridMeshNode())
                .with(GridMeshNode.U_TILES, 1)
                .with(GridMeshNode.V_TILES, 1)
                .with(GridMeshNode.U_TILE_SIZE, TILE_SIZE)
                .with(GridMeshNode.V_TILE_SIZE, TILE_SIZE)
                .with(GridMeshNode.TRIANGULATE, triangulate)
                .eval()
                .output(GridMeshNode.MESH, GeometryBundle.class);
    }

    /**
     * A unit cube from the {@code cube} primitive.
     *
     * @return the cube as a geometry bundle
     */
    private static GeometryBundle cube() {
        return new MapNodeContext(new CubeMeshNode())
                .with(CubeMeshNode.SIZE, TILE_SIZE)
                .eval()
                .output(CubeMeshNode.MESH, GeometryBundle.class);
    }

    /**
     * Moves a bundle through {@code transform_geometry}.
     *
     * @param geometry bundle to move
     * @param x        offset along X
     * @param y        offset along Y
     * @param z        offset along Z
     * @return the moved bundle
     */
    private static GeometryBundle translated(GeometryBundle geometry, float x, float y, float z) {
        return new MapNodeContext(new TransformGeometryNode())
                .with(TransformGeometryNode.GEOMETRY, geometry)
                .with(TransformGeometryNode.TRANSLATION, new Vector3Value(x, y, z))
                .eval()
                .output(TransformGeometryNode.GEOMETRY_OUT, GeometryBundle.class);
    }

    /**
     * Combines two bundles through {@code join_geometry}.
     *
     * @param first         first bundle
     * @param second        second bundle
     * @param mergeDistance weld radius; zero leaves both inputs' vertices as they are
     * @return the combined bundle
     */
    private static GeometryBundle join(GeometryBundle first, GeometryBundle second,
            float mergeDistance) {
        return new MapNodeContext(new JoinGeometryNode())
                .with(JoinGeometryNode.A, first)
                .with(JoinGeometryNode.B, second)
                .with(JoinGeometryNode.MERGE_DISTANCE, mergeDistance)
                .eval()
                .output(JoinGeometryNode.GEOMETRY, GeometryBundle.class);
    }
}
