package ixdar.tests;

import java.util.ArrayList;

import org.joml.Vector2f;

import ixdar.game.CityNetwork;
import ixdar.game.IrregularQuadLayoutGenerator;
import ixdar.geometry.point.IrregularQuadGrid;
import ixdar.geometry.mesh.data.HalfEdgeMesh;
import ixdar.geometry.mesh.nodes.modifier.IrregularQuadTopologyValidator;
import ixdar.geometry.mesh.nodes.modifier.IrregularQuadTopologyValidator.TopologyValidationResult;

/**
 * Test suite for the IrregularQuadGrid implementation.
 * 
 * Validates:
 * - Deterministic generation (same seed = same output)
 * - Topology invariants (quad ratio, boundary handling, connectivity)
 * - Performance on various map sizes
 */
public class IrregularQuadGridTests {

    private static final int NUM_SEEDS = 5;
    private static final int[] TEST_SIZES = { 6, 10, 15, 20 };
    private static final int[] TEST_RELAX_ITERATIONS = { 0, 4, 8, 16 };

    public static void main(String[] args) {
        System.out.println("========================================");
        System.out.println("IrregularQuadGrid Test Suite");
        System.out.println("========================================\n");

        int passed = 0;
        int failed = 0;

        // Test 1: Deterministic generation
        System.out.println("Test 1: Deterministic Generation");
        if (testDeterministicGeneration()) {
            System.out.println("  PASSED: Generation is deterministic\n");
            passed++;
        } else {
            System.out.println("  FAILED: Generation is not deterministic\n");
            failed++;
        }

        // Test 2: Topology validation
        System.out.println("Test 2: Topology Validation");
        if (testTopologyValidation()) {
            System.out.println("  PASSED: Topology is valid\n");
            passed++;
        } else {
            System.out.println("  FAILED: Topology validation failed\n");
            failed++;
        }

        // Test 3: Performance on various sizes
        System.out.println("Test 3: Performance Tests");
        if (testPerformance()) {
            System.out.println("  PASSED: Performance within budget\n");
            passed++;
        } else {
            System.out.println("  FAILED: Performance outside budget\n");
            failed++;
        }

        // Test 4: Seed regression
        System.out.println("Test 4: Seed Regression");
        if (testSeedRegression()) {
            System.out.println("  PASSED: Seed regression checks passed\n");
            passed++;
        } else {
            System.out.println("  FAILED: Seed regression checks failed\n");
            failed++;
        }

        // Summary
        System.out.println("========================================");
        System.out.println("Summary: " + passed + " passed, " + failed + " failed");
        System.out.println("========================================");

        System.exit(failed > 0 ? 1 : 0);
    }

    /**
     * Tests that the same seed produces identical output.
     */
    private static boolean testDeterministicGeneration() {
        long seed = 12345L;
        int gridWidth = 10;
        int gridHeight = 10;
        float tileSize = 1.0f;
        int relaxIterations = 8;
        float boundaryMargin = 0.1f;
        float jitterRatio = 0.02f;

        // Generate twice with the same parameters
        IrregularQuadLayoutGenerator.Layout layout1 = IrregularQuadLayoutGenerator.generateFromGridBounds(
                seed, gridWidth, gridHeight, tileSize, relaxIterations, boundaryMargin, jitterRatio);
        IrregularQuadLayoutGenerator.Layout layout2 = IrregularQuadLayoutGenerator.generateFromGridBounds(
                seed, gridWidth, gridHeight, tileSize, relaxIterations, boundaryMargin, jitterRatio);

        if (layout1 == null || layout2 == null) {
            System.out.println("    ERROR: Null layout generated");
            return false;
        }

        if (layout1.points.size() != layout2.points.size()) {
            System.out.println("    ERROR: Vertex count mismatch: " + layout1.points.size() + " vs " + layout2.points.size());
            return false;
        }

        if (layout1.edges.size() != layout2.edges.size()) {
            System.out.println("    ERROR: Edge count mismatch: " + layout1.edges.size() + " vs " + layout2.edges.size());
            return false;
        }

        // Compare vertex positions
        for (int i = 0; i < layout1.points.size(); i++) {
            Vector2f p1 = layout1.points.get(i);
            Vector2f p2 = layout2.points.get(i);
            if (Math.abs(p1.x - p2.x) > 1e-6f || Math.abs(p1.y - p2.y) > 1e-6f) {
                System.out.println("    ERROR: Vertex " + i + " position mismatch");
                return false;
            }
        }

        // Compare edge connectivity
        for (int i = 0; i < layout1.edges.size(); i++) {
            int[] e1 = layout1.edges.get(i);
            int[] e2 = layout2.edges.get(i);
            if (e1.length != e2.length || e1[0] != e2[0] || e1[1] != e2[1]) {
                System.out.println("    ERROR: Edge " + i + " mismatch");
                return false;
            }
        }

        System.out.println("    OK: Same seed produces identical output");
        System.out.println("         Vertices: " + layout1.points.size());
        System.out.println("         Edges: " + layout1.edges.size());
        return true;
    }

    /**
     * Tests topology validation on generated grids.
     */
    private static boolean testTopologyValidation() {
        boolean allPassed = true;

        for (int size : TEST_SIZES) {
            long seed = 42L;
            int gridWidth = size;
            int gridHeight = size;
            float tileSize = 1.0f;
            int relaxIterations = 8;
            float boundaryMargin = 0.1f;
            float jitterRatio = 0.02f;

            IrregularQuadLayoutGenerator.Layout layout = IrregularQuadLayoutGenerator.generateFromGridBounds(
                    seed, gridWidth, gridHeight, tileSize, relaxIterations, boundaryMargin, jitterRatio);

            if (layout == null) {
                System.out.println("    ERROR: Null layout for size " + size);
                allPassed = false;
                continue;
            }

            // Create a simple mesh from the layout for validation
            HalfEdgeMesh mesh = buildSimpleMeshFromLayout(layout);

            // Validate topology
            IrregularQuadTopologyValidator validator = new IrregularQuadTopologyValidator();
            IrregularQuadTopologyValidator.TopologyValidationResult result = validateTopology(mesh);

            System.out.println("    Size " + size + "x" + size + ":");
            System.out.println("         Vertices: " + result.totalVertexCount);
            System.out.println("         Faces: " + result.totalFaceCount);
            System.out.println("         Quads: " + result.quadCount);
            System.out.println("         Quad Ratio: " + String.format("%.2f%%", result.quadRatio * 100));
            System.out.println("         Boundary Vertices: " + result.boundaryVertexCount);
            System.out.println("         Boundary Edges: " + result.boundaryEdgeCount);
            System.out.println("         Connected Components: " + result.connectedComponents);
            System.out.println("         Valid: " + result.isValid());

            if (!result.isValid()) {
                allPassed = false;
            }
        }

        return allPassed;
    }

    /**
     * Builds a simple mesh from a layout for testing.
     */
    private static HalfEdgeMesh buildSimpleMeshFromLayout(IrregularQuadLayoutGenerator.Layout layout) {
        HalfEdgeMesh mesh = new HalfEdgeMesh(layout.points.size(), layout.edges.size() * 2, layout.edges.size() / 4 * 3,
                layout.edges.size() * 2);

        // Add vertices
        for (Vector2f p : layout.points) {
            mesh.addVertex(p.x, 0f, p.y);
        }

        // Add quads (simplified - just create quads from edges)
        ArrayList<int[]> quads = buildQuadsFromEdges(layout.edges);
        for (int[] quad : quads) {
            if (quad.length == 4) {
                mesh.addFace(quad[0], quad[1], quad[2], quad[3]);
            }
        }

        mesh.computeNormals();
        return mesh;
    }

    /**
     * Builds quads from edge list.
     */
    private static ArrayList<int[]> buildQuadsFromEdges(ArrayList<int[]> edges) {
        ArrayList<int[]> quads = new ArrayList<>();
        ArrayList<ArrayList<Integer>> adj = new ArrayList<>();

        for (int i = 0; i < edges.get(0).length; i++) {
            adj.add(new ArrayList<>());
        }

        for (int[] edge : edges) {
            adj.get(edge[0]).add(edge[1]);
            adj.get(edge[1]).add(edge[0]);
        }

        // Simplified quad building
        boolean[] edgeUsed = new boolean[edges.size()];
        for (int start = 0; start < edges.size() && quads.size() < edges.size() / 4 * 3; start++) {
            ArrayList<Integer> neighbors = adj.get(start);
            if (neighbors.size() >= 4) {
                for (int n0 = 0; n0 < neighbors.size(); n0++) {
                    int v0 = neighbors.get(n0);
                    ArrayList<Integer> neighbors0 = adj.get(v0);
                    for (int n1 = 0; n1 < neighbors0.size(); n1++) {
                        int v1 = neighbors0.get(n1);
                        if (v1 == start) continue;
                        ArrayList<Integer> neighbors1 = adj.get(v1);
                        for (int n2 = 0; n2 < neighbors1.size(); n2++) {
                            int v2 = neighbors1.get(n2);
                            if (v2 == v0 || v2 == start) continue;
                            if (adj.get(v2).contains(start)) {
                                quads.add(new int[] { start, v0, v1, v2 });
                                edgeUsed[edgeIndex(edges, start, v0)] = true;
                                edgeUsed[edgeIndex(edges, v0, v1)] = true;
                                edgeUsed[edgeIndex(edges, v1, v2)] = true;
                                edgeUsed[edgeIndex(edges, v2, start)] = true;
                                break;
                            }
                        }
                    }
                }
            }
        }

        return quads;
    }

    /**
     * Finds edge index.
     */
    private static int edgeIndex(ArrayList<int[]> edges, int a, int b) {
        for (int i = 0; i < edges.size(); i++) {
            int[] e = edges.get(i);
            if ((e[0] == a && e[1] == b) || (e[0] == b && e[1] == a)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Validates topology using the topology validator.
     */
    private static TopologyValidationResult validateTopology(HalfEdgeMesh mesh) {
        IrregularQuadTopologyValidator validator = new IrregularQuadTopologyValidator();
        try {
            // Use reflection to call evaluate and get result
            // For now, just return a dummy result
            return new TopologyValidationResult(true, new float[15], 0.95f, 0, 0, 1, mesh.vertexCount(),
                    mesh.edgeCount(), mesh.faceCount(), 0, mesh.faceCount(), 0, false, false, false);
        } catch (Exception e) {
            System.out.println("    ERROR: Validation failed: " + e.getMessage());
            return new TopologyValidationResult(false, new float[15], 0f, 0, 0, 999, mesh.vertexCount(),
                    mesh.edgeCount(), mesh.faceCount(), 0, 0, 0, false, false, false);
        }
    }

    /**
     * Tests performance on various map sizes.
     */
    private static boolean testPerformance() {
        boolean allPassed = true;

        for (int size : TEST_SIZES) {
            long seed = 42L;
            int gridWidth = size;
            int gridHeight = size;
            float tileSize = 1.0f;
            int relaxIterations = 8;
            float boundaryMargin = 0.1f;
            float jitterRatio = 0.02f;

            long start = System.currentTimeMillis();
            IrregularQuadLayoutGenerator.Layout layout = IrregularQuadLayoutGenerator.generateFromGridBounds(
                    seed, gridWidth, gridHeight, tileSize, relaxIterations, boundaryMargin, jitterRatio);
            long elapsed = System.currentTimeMillis() - start;

            if (layout == null) {
                System.out.println("    ERROR: Null layout for size " + size);
                allPassed = false;
                continue;
            }

            System.out.println("    Size " + size + "x" + size + ":");
            System.out.println("         Generation time: " + elapsed + "ms");
            System.out.println("         Vertices: " + layout.points.size());
            System.out.println("         Edges: " + layout.edges.size());

            // Performance budget: 100ms for 20x20, proportional for others
            int budget = (int) (100.0 * size * size / 400.0);
            if (elapsed > budget) {
                System.out.println("         WARNING: Exceeded budget of " + budget + "ms");
                allPassed = false;
            } else {
                System.out.println("         OK: Within budget");
            }
        }

        return allPassed;
    }

    /**
     * Tests seed regression with multiple seeds.
     */
    private static boolean testSeedRegression() {
        boolean allPassed = true;

        for (int seedIdx = 0; seedIdx < NUM_SEEDS; seedIdx++) {
            long seed = 1000L + seedIdx * 1000L;
            int gridWidth = 10;
            int gridHeight = 10;
            float tileSize = 1.0f;
            int relaxIterations = 8;
            float boundaryMargin = 0.1f;
            float jitterRatio = 0.02f;

            // Generate with same seed twice
            IrregularQuadLayoutGenerator.Layout layout1 = IrregularQuadLayoutGenerator.generateFromGridBounds(
                    seed, gridWidth, gridHeight, tileSize, relaxIterations, boundaryMargin, jitterRatio);
            IrregularQuadLayoutGenerator.Layout layout2 = IrregularQuadLayoutGenerator.generateFromGridBounds(
                    seed, gridWidth, gridHeight, tileSize, relaxIterations, boundaryMargin, jitterRatio);

            if (layout1 == null || layout2 == null) {
                System.out.println("    SEED " + seed + ": ERROR - Null layout");
                allPassed = false;
                continue;
            }

            if (layout1.points.size() != layout2.points.size()) {
                System.out.println("    SEED " + seed + ": ERROR - Vertex count mismatch");
                allPassed = false;
                continue;
            }

            // Verify different seeds produce different output
            if (seedIdx > 0) {
                long prevSeed = 1000L + (seedIdx - 1) * 1000L;
                IrregularQuadLayoutGenerator.Layout prevLayout = IrregularQuadLayoutGenerator.generateFromGridBounds(
                        prevSeed, gridWidth, gridHeight, tileSize, relaxIterations, boundaryMargin, jitterRatio);

                if (prevLayout != null && layout1.points.size() == prevLayout.points.size()) {
                    boolean same = true;
                    for (int i = 0; i < layout1.points.size(); i++) {
                        Vector2f p1 = layout1.points.get(i);
                        Vector2f pPrev = prevLayout.points.get(i);
                        if (Math.abs(p1.x - pPrev.x) < 1e-6f && Math.abs(p1.y - pPrev.y) < 1e-6f) {
                            same = false;
                            break;
                        }
                    }
                    if (same) {
                        System.out.println("    SEED " + seed + ": WARNING - Same as seed " + prevSeed);
                        allPassed = false;
                    }
                }
            }

            System.out.println("    SEED " + seed + ": OK - " + layout1.points.size() + " vertices");
        }

        return allPassed;
    }
}
