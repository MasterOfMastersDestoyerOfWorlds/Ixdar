package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.curve.FloatCurveKernel;

public class FloatCurveKernelTest {

    @Test
    public void linearInterpolateBetweenPoints() {
        FloatCurveKernel k = FloatCurveKernel.fromCommaSeparatedPairs("0,0,1,1");
        assertEquals(0f, k.evaluate(0f), 1e-5f);
        assertEquals(0.5f, k.evaluate(0.5f), 1e-5f);
        assertEquals(1f, k.evaluate(1f), 1e-5f);
    }

    @Test
    public void depthDefaultMiddleSample() {
        FloatCurveKernel k = FloatCurveKernel.fromCommaSeparatedPairs("0,0,1,0.775,0.423,0.537");
        assertEquals(0.537f, k.evaluate(0.423f), 1e-3f);
    }
}
