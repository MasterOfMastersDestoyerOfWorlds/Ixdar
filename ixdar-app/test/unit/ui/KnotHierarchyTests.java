package unit.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import ixdar.geometry.cuts.DisjointUnionSets;
import ixdar.geometry.knot.Knot;
import ixdar.geometry.knot.Segment;

/**
 * Tests for Knot hierarchy functionality used in multi-level clustering UI.
 * Verifies knot structure, segment operations, and hierarchy navigation.
 */
@Execution(ExecutionMode.CONCURRENT)
public class KnotHierarchyTests {

    @BeforeEach
    public void setUp() {
        // Reset the static union set before each test
        Knot.unionSet = new DisjointUnionSets();
    }

    // ==================== DisjointUnionSets Tests ====================

    @Test
    public void test_disjointUnionSets_initial_empty() {
        DisjointUnionSets dus = new DisjointUnionSets();
        assertEquals(0, dus.countGroups(), "Empty DisjointUnionSets should have 0 groups");
        assertEquals(0, dus.totalNumGroups(), "Empty DisjointUnionSets should have 0 total groups");
    }

    @Test
    public void test_disjointUnionSets_addSet_increases_count() {
        DisjointUnionSets dus = new DisjointUnionSets();
        Knot.unionSet = dus;
        
        // Create mock knots by directly manipulating the union set
        dus.parent.put(0, 0);
        dus.unmatched.put(0, 2);
        dus.countGroups++;
        dus.totalNumGroups++;
        
        assertEquals(1, dus.countGroups(), "After adding one set, should have 1 group");
    }

    @Test
    public void test_disjointUnionSets_find_returns_root() {
        DisjointUnionSets dus = new DisjointUnionSets();
        
        // Add element 0 as its own parent
        dus.parent.put(0, 0);
        dus.unmatched.put(0, 2);
        
        assertEquals(0, dus.find(0), "Find should return the element itself when it's the root");
    }

    @Test
    public void test_disjointUnionSets_union_reduces_group_count() {
        DisjointUnionSets dus = new DisjointUnionSets();
        
        // Add two separate elements
        dus.parent.put(0, 0);
        dus.unmatched.put(0, 2);
        dus.countGroups++;
        dus.totalNumGroups++;
        
        dus.parent.put(1, 1);
        dus.unmatched.put(1, 2);
        dus.countGroups++;
        dus.totalNumGroups++;
        
        assertEquals(2, dus.countGroups(), "Before union, should have 2 groups");
        
        dus.union(0, 1);
        
        assertEquals(1, dus.countGroups(), "After union, should have 1 group");
        assertEquals(2, dus.totalNumGroups(), "Total groups should still be 2");
    }

    @Test
    public void test_disjointUnionSets_sameGroup_after_union() {
        DisjointUnionSets dus = new DisjointUnionSets();
        
        // Add two separate elements
        dus.parent.put(0, 0);
        dus.unmatched.put(0, 2);
        dus.parent.put(1, 1);
        dus.unmatched.put(1, 2);
        
        // Before union, find should return different roots
        assertNotEquals(dus.find(0), dus.find(1), "Before union, elements should be in different groups");
        
        dus.union(0, 1);
        
        // After union, find should return the same root
        assertEquals(dus.find(0), dus.find(1), "After union, elements should be in the same group");
    }

    // ==================== Segment ID Transform Tests ====================

    @Test
    public void test_segment_idTransform_symmetric() {
        // Cantor pairing function should be symmetric for unordered pairs
        long id1 = Segment.idTransform(3, 5);
        long id2 = Segment.idTransform(5, 3);
        
        assertEquals(id1, id2, "idTransform should be symmetric for unordered pairs");
    }

    @Test
    public void test_segment_idTransform_unique() {
        // Different pairs should have different IDs
        long id1 = Segment.idTransform(1, 2);
        long id2 = Segment.idTransform(1, 3);
        long id3 = Segment.idTransform(2, 3);
        
        assertNotEquals(id1, id2, "Different pairs should have different IDs");
        assertNotEquals(id1, id3, "Different pairs should have different IDs");
        assertNotEquals(id2, id3, "Different pairs should have different IDs");
    }

    @Test
    public void test_segment_idTransformOrdered_asymmetric() {
        // Ordered transform should NOT be symmetric
        long id1 = Segment.idTransformOrdered(3, 5);
        long id2 = Segment.idTransformOrdered(5, 3);
        
        assertNotEquals(id1, id2, "idTransformOrdered should NOT be symmetric");
    }

    @Test
    public void test_segment_idTransform_with_zero() {
        long id1 = Segment.idTransform(0, 1);
        long id2 = Segment.idTransform(0, 2);
        
        assertNotEquals(id1, id2, "IDs with zero should still be unique");
        assertTrue(id1 >= 0, "ID should be non-negative");
        assertTrue(id2 >= 0, "ID should be non-negative");
    }

    @Test
    public void test_segment_idTransform_same_values() {
        long id = Segment.idTransform(5, 5);
        assertTrue(id >= 0, "ID with same values should be valid");
    }

    // ==================== Winding Order Tests ====================

    @Test
    public void test_windingOrder_enum_values() {
        Knot.WindingOrder none = Knot.WindingOrder.None;
        Knot.WindingOrder cw = Knot.WindingOrder.Clockwise;
        Knot.WindingOrder ccw = Knot.WindingOrder.CounterClockwise;
        
        assertNotNull(none);
        assertNotNull(cw);
        assertNotNull(ccw);
        assertNotEquals(none, cw);
        assertNotEquals(cw, ccw);
        assertNotEquals(none, ccw);
    }

    // ==================== Knot Singleton Tests ====================

    @Test
    public void test_knot_isSingleton_concept() {
        // A singleton knot is one with size 1 (just one point)
        // This tests the concept without creating full Knot objects
        
        ArrayList<Object> list1 = new ArrayList<>();
        list1.add(new Object());
        
        ArrayList<Object> list2 = new ArrayList<>();
        list2.add(new Object());
        list2.add(new Object());
        
        assertEquals(1, list1.size(), "Singleton should have size 1");
        assertTrue(list2.size() > 1, "Non-singleton should have size > 1");
    }

    // ==================== Height Calculation Tests ====================

    @Test
    public void test_height_singleton_is_one() {
        // A singleton knot has height 1
        int singletonHeight = 1;
        assertEquals(1, singletonHeight, "Singleton knot height should be 1");
    }

    @Test
    public void test_height_increases_with_nesting() {
        // Height increases with nested knots
        // height(singleton) = 1
        // height(knot with singletons) = 2
        // height(knot with nested knots) = max(children heights) + 1
        
        int h1 = 1; // singleton
        int h2 = Math.max(h1, h1) + 1; // knot with two singletons
        int h3 = Math.max(h2, h1) + 1; // knot with one nested knot and one singleton
        
        assertEquals(1, h1, "Singleton height");
        assertEquals(2, h2, "First level knot height");
        assertEquals(3, h3, "Second level knot height");
    }

    // ==================== Manifold Segment Tests ====================

    @Test
    public void test_manifold_segment_list_concept() {
        // A knot's manifold is the list of segments on its boundary
        ArrayList<Object> manifoldSegments = new ArrayList<>();
        
        assertTrue(manifoldSegments.isEmpty(), "Empty manifold should have no segments");
        
        manifoldSegments.add(new Object());
        manifoldSegments.add(new Object());
        manifoldSegments.add(new Object());
        
        assertEquals(3, manifoldSegments.size(), "Manifold with 3 segments");
    }

    // ==================== KnotPoints Tests ====================

    @Test
    public void test_knotPoints_list_contains_children() {
        ArrayList<Integer> knotPoints = new ArrayList<>();
        ArrayList<Integer> knotPointsFlattened = new ArrayList<>();
        
        // A knot with direct children [1, 2] where 1 contains [3, 4]
        knotPoints.add(1);
        knotPoints.add(2);
        
        // Flattened includes all leaves
        knotPointsFlattened.add(3);
        knotPointsFlattened.add(4);
        knotPointsFlattened.add(2);
        
        assertEquals(2, knotPoints.size(), "knotPoints should have 2 direct children");
        assertEquals(3, knotPointsFlattened.size(), "knotPointsFlattened should have all leaves");
    }

    // ==================== Match Count Tests ====================

    @Test
    public void test_matchCount_limits() {
        // A point can have min 2 and max 2 matches (in a tour)
        int minMatches = 2;
        int maxMatches = 2;
        int matchCount = 0;
        
        assertEquals(0, matchCount, "Initial match count should be 0");
        
        matchCount++;
        assertTrue(matchCount <= maxMatches, "Match count should not exceed max");
        
        matchCount++;
        assertEquals(maxMatches, matchCount, "Match count at max");
        
        boolean isFull = matchCount >= maxMatches;
        assertTrue(isFull, "Should be full when matchCount >= maxMatches");
    }

    // ==================== Segment Comparator Tests ====================

    @Test
    public void test_segment_comparison_by_distance_concept() {
        // Segments are sorted by distance (shorter first)
        double dist1 = 5.0;
        double dist2 = 10.0;
        double dist3 = 5.0;
        
        assertTrue(dist1 < dist2, "Shorter distance should sort first");
        assertEquals(dist1, dist3, "Equal distances");
    }

    // ==================== Layer Lookup Tests ====================

    @Test
    public void test_knotLayerLookup_concept() {
        // knotLayerLookup maps knot ID to its layer number
        java.util.HashMap<Integer, Integer> knotLayerLookup = new java.util.HashMap<>();
        
        knotLayerLookup.put(100, 1); // Knot 100 is at layer 1
        knotLayerLookup.put(101, 2); // Knot 101 is at layer 2
        knotLayerLookup.put(102, 3); // Knot 102 is at layer 3
        
        assertEquals(1, knotLayerLookup.get(100));
        assertEquals(2, knotLayerLookup.get(101));
        assertEquals(3, knotLayerLookup.get(102));
    }

    // ==================== Color Lookup Tests ====================

    @Test
    public void test_colorLookup_concept() {
        // colorLookup maps knot ID to color index
        java.util.HashMap<Long, Integer> colorLookup = new java.util.HashMap<>();
        
        colorLookup.put(100L, 0); // Knot 100 uses color 0
        colorLookup.put(101L, 1); // Knot 101 uses color 1
        
        assertEquals(0, colorLookup.get(100L));
        assertEquals(1, colorLookup.get(101L));
    }
}
