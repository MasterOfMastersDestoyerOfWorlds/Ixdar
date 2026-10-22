package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;

import org.junit.jupiter.api.Test;

import ixdar.numeric.graph.Dijkstra;

/**
 * The shared Dijkstra core settles equal distances in exactly the order the private
 * {@link PriorityQueue} copies it replaced did, so the copies' outputs hold bit for bit; it
 * relaxes strictly, reopens a settled node a shorter distance reaches, and starts every run clean.
 */
class DijkstraTest {

    private static final int NODES = 4000;

    private static final int DISTINCT_DISTANCES = 7;

    private static final long SEED = 63L;

    private static final int POP_ONE_IN = 3;

    @Test
    void equalDistancesSettleInPriorityQueueOrder() {
        Random random = new Random(SEED);
        Dijkstra walk = new Dijkstra();
        walk.start(NODES);
        PriorityQueue<double[]> reference = new PriorityQueue<>(
                Comparator.comparingDouble(entry -> entry[0]));
        List<Integer> walkOrder = new ArrayList<>();
        List<Integer> referenceOrder = new ArrayList<>();
        for (int node = 0; node < NODES; node++) {
            double distance = random.nextInt(DISTINCT_DISTANCES);
            walk.relax(node, distance, -1);
            reference.add(new double[] { distance, node });
            if (random.nextInt(POP_ONE_IN) == 0) {
                walkOrder.add(walk.settle());
                referenceOrder.add((int) reference.poll()[1]);
            }
        }
        for (int node = walk.settle(); node >= 0; node = walk.settle()) {
            walkOrder.add(node);
        }
        while (!reference.isEmpty()) {
            referenceOrder.add((int) reference.poll()[1]);
        }
        assertEquals(referenceOrder, walkOrder);
    }

    @Test
    void relaxationIsStrictAndAShorterDistanceReopensASettledNode() {
        Dijkstra walk = new Dijkstra();
        walk.start(3);
        assertTrue(walk.relax(0, 2.0, -1));
        assertFalse(walk.relax(0, 2.0, 1), "an equal distance never replaces the first");
        assertEquals(-1, walk.via(0));
        assertEquals(0, walk.settle());
        assertTrue(walk.isSettled(0));
        assertTrue(walk.relax(0, 1.0, 2));
        assertFalse(walk.isSettled(0));
        assertEquals(0, walk.settle());
        assertEquals(1.0, walk.distance(0));
        assertEquals(2, walk.via(0));
        assertEquals(-1, walk.settle());
        assertFalse(walk.isReached(1));
        assertEquals(Double.POSITIVE_INFINITY, walk.distance(1));
        assertFalse(walk.relax(1, Double.POSITIVE_INFINITY, 0), "infinity reaches nothing");
    }

    @Test
    void aNewRunForgetsTheLast() {
        Dijkstra walk = new Dijkstra();
        walk.start(2);
        walk.relax(1, 0.5, -1);
        walk.start(2);
        assertFalse(walk.isReached(1));
        assertEquals(-1, walk.settle());
        assertTrue(walk.relax(1, 3.0, -1));
    }

    @Test
    void aPriorityOrdersSettlingApartFromDistance() {
        Dijkstra walk = new Dijkstra();
        walk.start(2);
        walk.relax(0, 1.0, 5.0, -1);
        walk.relax(1, 4.0, 2.0, -1);
        assertEquals(1, walk.settle());
        assertEquals(4.0, walk.distance(1));
        assertEquals(0, walk.settle());
    }
}
