package unit.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.joml.Vector2f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ixdar.graphics.cameras.Camera2D;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.sdf.SDFCircle;
import ixdar.graphics.render.sdf.ShaderDrawable.Quad;
import ixdar.graphics.render.shaders.ShaderProgram;
import ixdar.platform.Platform;
import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;
import unit.GlStandIn;

/**
 * {@link SDFCircle} uploads the square around its circle, where it once drew a degenerate quad at
 * the origin, and a drawable re-uploads its vertices when only its color changes.
 */
class SDFCircleTest {

    private static final float TOLERANCE = 1e-5f;

    private static final Vector2f CENTER = new Vector2f(100f, 50f);

    private static final float RADIUS = 10f;

    private Platform suitePlatform;

    private GL suiteGl;

    private Camera2D camera;

    /**
     * Pair the suite's platform with a GL that does nothing, so the circle's shader builds.
     */
    @BeforeEach
    void installGlStandIn() {
        suitePlatform = Platforms.get();
        suiteGl = Platforms.gl();
        Platforms.init(suitePlatform, GlStandIn.create());
        camera = new Camera2D(1, 1, 1f, 0, 0, null);
    }

    /**
     * Put the suite's GL back.
     */
    @AfterEach
    void restoreSuiteGl() {
        Platforms.init(suitePlatform, suiteGl);
    }

    @Test
    void drawnQuadIsTheSquareAroundTheCircle() {
        SDFCircle circle = new SDFCircle();
        circle.draw(CENTER, RADIUS, Color.TEAL, camera);
        Quad quad = circle.getQuad();
        assertEquals(CENTER.x - RADIUS, quad.bottomLeft.x, TOLERANCE);
        assertEquals(CENTER.y - RADIUS, quad.bottomLeft.y, TOLERANCE);
        assertEquals(CENTER.x + RADIUS, quad.topRight.x, TOLERANCE);
        assertEquals(CENTER.y + RADIUS, quad.topRight.y, TOLERANCE);
    }

    @Test
    void colorChangeAloneReuploadsTheVertices() {
        SDFCircle circle = new SDFCircle();
        camera.resetZIndex();
        circle.draw(CENTER, RADIUS, Color.TEAL, camera);
        camera.resetZIndex();
        circle.draw(CENTER, RADIUS, Color.RED, camera);
        assertEquals(Color.RED.toVector4f(), circle.getShader().uniformMap.get(ShaderProgram.VERTEXCOLOR));
    }
}
