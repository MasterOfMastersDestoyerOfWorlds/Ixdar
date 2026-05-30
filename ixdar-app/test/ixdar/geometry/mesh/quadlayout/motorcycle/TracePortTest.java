package ixdar.geometry.mesh.quadlayout.motorcycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import ixdar.geometry.mesh.quadlayout.seamless.SeamlessParameterization;

/**
 * Unit tests for QEx Algorithm 4 port enumeration.
 */
class TracePortTest {

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void cubeCornerEmitsThreeForwardValidPorts() {
        SeamlessParameterization seamless = CubeCornerFixtures.buildSeamless();
        List<TracePort> ports = TracePort.spawnFromSingularities(seamless);
        assertEquals(CubeCornerFixtures.PORTS_PER_CORNER, ports.size(),
                "cube corner should emit three QEx ports");

        ChartWalker walker = new ChartWalker(seamless);
        Set<String> directions = new HashSet<>();
        for (TracePort port : ports) {
            assertEquals(CubeCornerFixtures.CORNER_VERTEX, port.singularityVertexId);
            double[] cornerUv = new double[ChartWalker.CORNER_UV_FLOATS];
            walker.faceCornerUv(port.activeFace, cornerUv);
            double startU = cornerUv[port.cornerIndex * 2];
            double startV = cornerUv[port.cornerIndex * 2 + 1];
            ChartWalker.State probe = new ChartWalker.State(
                    port.activeFace, startU, startV, port.axis, port.sign);
            assertNotNull(walker.nextEdgeHit(probe),
                    "port must have a forward edge hit from spawn");
            directions.add(port.axis.name() + port.sign);
        }
        assertTrue(directions.size() >= 2,
                "three ports should span multiple axis/sign sectors, got " + directions);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void spawnFromSingularitiesMatchesCornerCountOnCubeCornerFan() {
        SeamlessParameterization seamless = CubeCornerFixtures.buildSeamless();
        List<TracePort> ports = TracePort.spawnFromSingularities(seamless);
        assertEquals(CubeCornerFixtures.PORTS_PER_CORNER, ports.size(),
                "single cube-corner singularity should emit three QEx ports");
    }
}
