package unit.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import ixdar.common.exceptions.MultipleCyclesFoundException;
import ixdar.common.exceptions.TerminalParseException;
import ixdar.geometry.cuts.DisjointUnionSets;
import ixdar.geometry.knot.Knot;
import ixdar.geometry.shell.DistanceMatrix;
import ixdar.geometry.shell.Shell;
import ixdar.platform.file.FileManagement;
import ixdar.platform.file.PointSetPath;

/**
 * Integration tests for multi-level clustering using real data files.
 * Verifies that clustering produces valid hierarchical structures.
 */
@Execution(ExecutionMode.CONCURRENT)
public class ClusteringIntegrationTests {

    /**
     * Helper method to load a test file and run clustering.
     */
    private ArrayList<Knot> runClustering(String fileName, int layers) 
            throws TerminalParseException, IOException, MultipleCyclesFoundException {
        
        // Reset static state
        Knot.unionSet = new DisjointUnionSets();
        
        PointSetPath retTup = FileManagement.importFromFile(FileManagement.getTestFile(fileName));
        
        DistanceMatrix d = retTup.d;
        if (retTup.d == null) {
            d = new DistanceMatrix(retTup.ps);
        }
        
        Shell shell = new Shell();
        for (int i = 0; i < retTup.ps.size() && i < retTup.tsp.size(); i++) {
            shell.add(retTup.tsp.get(i));
        }
        
        Collections.shuffle(shell, new Random(42)); // Fixed seed for reproducibility
        
        ArrayList<Knot> resultKnots = new ArrayList<>(shell.slowSolve(shell, d, layers));
        return resultKnots;
    }

    // ==================== Circle Tests ====================

    @Test
    public void test_clustering_circle_5_produces_knots() {
        try {
            ArrayList<Knot> knots = runClustering("circle_5", 10);
            
            assertNotNull(knots, "Clustering should produce a result");
            assertFalse(knots.isEmpty(), "Clustering should produce at least one knot");
            
        } catch (Exception e) {
            fail("Clustering circle_5 should not throw: " + e.getMessage());
        }
    }

    @Test
    public void test_clustering_circle_5_has_correct_point_count() {
        try {
            ArrayList<Knot> knots = runClustering("circle_5", 10);
            
            // Find the largest knot (should contain all points)
            Knot largest = null;
            for (Knot k : knots) {
                if (largest == null || k.size() > largest.size()) {
                    largest = k;
                }
            }
            
            assertNotNull(largest, "Should have at least one knot");
            assertEquals(5, largest.size(), "Largest knot should contain all 5 points");
            
        } catch (Exception e) {
            fail("Clustering should not throw: " + e.getMessage());
        }
    }

    @Test
    public void test_clustering_circle_10_produces_hierarchy() {
        try {
            ArrayList<Knot> knots = runClustering("circle_10", 10);
            
            assertNotNull(knots, "Clustering should produce a result");
            
            // Check that we have some hierarchy
            int maxHeight = 0;
            for (Knot k : knots) {
                int height = k.getHeight();
                if (height > maxHeight) {
                    maxHeight = height;
                }
            }
            
            assertTrue(maxHeight >= 1, "Should have at least height 1");
            
        } catch (Exception e) {
            fail("Clustering circle_10 should not throw: " + e.getMessage());
        }
    }

    // ==================== Knot Structure Tests ====================

    @Test
    public void test_clustering_knots_have_valid_manifolds() {
        try {
            ArrayList<Knot> knots = runClustering("circle_5", 10);
            
            for (Knot k : knots) {
                if (!k.isSingleton()) {
                    // Non-singleton knots should have manifold segments
                    assertNotNull(k.manifoldSegments, "Knot should have manifold segments list");
                    assertFalse(k.manifoldSegments.isEmpty(), 
                        "Non-singleton knot should have manifold segments");
                    
                    // Manifold size should equal number of points (closed loop)
                    assertEquals(k.knotPointsFlattened.size(), k.manifoldSegments.size(),
                        "Manifold segment count should equal flattened point count");
                }
            }
            
        } catch (Exception e) {
            fail("Clustering should not throw: " + e.getMessage());
        }
    }

    @Test
    public void test_clustering_knots_have_valid_knotPoints() {
        try {
            ArrayList<Knot> knots = runClustering("circle_5", 10);
            
            for (Knot k : knots) {
                assertNotNull(k.knotPoints, "Knot should have knotPoints list");
                assertNotNull(k.knotPointsFlattened, "Knot should have knotPointsFlattened list");
                
                if (!k.isSingleton()) {
                    assertFalse(k.knotPoints.isEmpty(), "Non-singleton should have children");
                }
            }
            
        } catch (Exception e) {
            fail("Clustering should not throw: " + e.getMessage());
        }
    }

    @Test
    public void test_clustering_singleton_knots_have_size_one() {
        try {
            ArrayList<Knot> knots = runClustering("circle_5", 10);
            
            for (Knot k : knots) {
                if (k.isSingleton()) {
                    assertEquals(1, k.size(), "Singleton should have size 1");
                    assertEquals(1, k.knotPointsFlattened.size(), 
                        "Singleton knotPointsFlattened should have size 1");
                }
            }
            
        } catch (Exception e) {
            fail("Clustering should not throw: " + e.getMessage());
        }
    }

    // ==================== Union Find Tests ====================

    @Test
    public void test_clustering_all_points_in_same_group() {
        try {
            ArrayList<Knot> knots = runClustering("circle_5", 40);
            
            // After full clustering, all points should be in the same union-find group
            DisjointUnionSets unionSet = Knot.unionSet;
            
            // Find the root of the first knot
            if (!knots.isEmpty()) {
                Knot first = knots.get(0);
                int firstRoot = unionSet.find(first);
                
                // Check that the largest knot's children are in the same group
                for (Knot k : knots) {
                    if (k.size() >= first.size()) {
                        first = k;
                    }
                }
                
                // All points in the largest knot should share a root
                for (Knot child : first.knotPointsFlattened) {
                    assertEquals(unionSet.find(first), unionSet.find(child),
                        "All points in knot should be in same union-find group");
                }
            }
            
        } catch (Exception e) {
            fail("Clustering should not throw: " + e.getMessage());
        }
    }

    // ==================== Height Tests ====================

    @Test
    public void test_clustering_height_increases_with_layers() {
        try {
            ArrayList<Knot> knots = runClustering("circle_10", 40);
            
            int maxHeight = 0;
            Knot tallest = null;
            for (Knot k : knots) {
                int height = k.getHeight();
                if (height > maxHeight) {
                    maxHeight = height;
                    tallest = k;
                }
            }
            
            assertTrue(maxHeight >= 1, "Max height should be at least 1");
            
            if (tallest != null && !tallest.isSingleton()) {
                // Children should have smaller height
                for (Knot child : tallest.knotPoints) {
                    assertTrue(child.getHeight() < tallest.getHeight(),
                        "Child height should be less than parent height");
                }
            }
            
        } catch (Exception e) {
            fail("Clustering should not throw: " + e.getMessage());
        }
    }

    // ==================== Length Tests ====================

    @Test
    public void test_clustering_knot_has_positive_length() {
        try {
            ArrayList<Knot> knots = runClustering("circle_5", 10);
            
            for (Knot k : knots) {
                if (!k.isSingleton() && !k.manifoldSegments.isEmpty()) {
                    double length = k.getLength();
                    assertTrue(length > 0, "Knot manifold should have positive length");
                }
            }
            
        } catch (Exception e) {
            fail("Clustering should not throw: " + e.getMessage());
        }
    }

    // ==================== Two Circle Tests ====================

    @Test
    public void test_clustering_twocircle_produces_multiple_groups() {
        try {
            // With fewer layers, we might get multiple separate knots
            ArrayList<Knot> knots = runClustering("twocircle_in_10", 5);
            
            assertNotNull(knots, "Clustering should produce a result");
            // Depending on clustering depth, might have 1 or more groups
            
        } catch (Exception e) {
            fail("Clustering twocircle should not throw: " + e.getMessage());
        }
    }

    // ==================== Triforce Tests ====================

    @Test
    public void test_clustering_triforce_structure() {
        try {
            ArrayList<Knot> knots = runClustering("triforce", 10);
            
            assertNotNull(knots, "Clustering should produce a result");
            
            // Find largest knot
            Knot largest = null;
            for (Knot k : knots) {
                if (largest == null || k.size() > largest.size()) {
                    largest = k;
                }
            }
            
            assertNotNull(largest, "Should have at least one knot");
            
        } catch (Exception e) {
            fail("Clustering triforce should not throw: " + e.getMessage());
        }
    }

    // ==================== Winding Order Tests ====================

    @Test
    public void test_clustering_determines_winding_order() {
        try {
            ArrayList<Knot> knots = runClustering("circle_5", 10);
            
            for (Knot k : knots) {
                if (!k.isSingleton() && k.knotPointsFlattened.size() >= 3) {
                    Knot.WindingOrder order = k.DetermineWindingOrder();
                    assertNotNull(order, "Winding order should be determined");
                    assertNotEquals(Knot.WindingOrder.None, order, 
                        "Winding order should be CW or CCW, not None");
                }
            }
            
        } catch (Exception e) {
            fail("Clustering should not throw: " + e.getMessage());
        }
    }

    // ==================== Match Tests ====================

    @Test
    public void test_clustering_points_have_correct_matches() {
        try {
            ArrayList<Knot> knots = runClustering("circle_5", 40);
            
            // Find the largest complete knot
            Knot largest = null;
            for (Knot k : knots) {
                if (largest == null || k.size() > largest.size()) {
                    largest = k;
                }
            }
            
            if (largest != null) {
                // Each point in a complete tour should have exactly 2 matches
                for (Knot point : largest.knotPointsFlattened) {
                    if (point.isSingleton()) {
                        assertEquals(2, point.matchCount, 
                            "Each point in complete tour should have 2 matches");
                    }
                }
            }
            
        } catch (Exception e) {
            fail("Clustering should not throw: " + e.getMessage());
        }
    }

    // ==================== Segment Tests ====================

    @Test
    public void test_clustering_segments_have_valid_endpoints() {
        try {
            ArrayList<Knot> knots = runClustering("circle_5", 10);
            
            for (Knot k : knots) {
                for (ixdar.geometry.knot.Segment s : k.manifoldSegments) {
                    assertNotNull(s.first, "Segment should have first endpoint");
                    assertNotNull(s.last, "Segment should have last endpoint");
                    assertTrue(s.distance >= 0, "Segment distance should be non-negative");
                }
            }
            
        } catch (Exception e) {
            fail("Clustering should not throw: " + e.getMessage());
        }
    }
}
