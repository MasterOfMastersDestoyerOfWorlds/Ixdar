package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import ixdar.annotations.meshnode.MapNodeContext;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.Vector3Value;
import ixdar.geometry.mesh.documentation.MeshNodeCatalog;
import ixdar.geometry.mesh.nodes.math.BooleanMathNode;
import ixdar.geometry.mesh.nodes.math.CompareNode;
import ixdar.geometry.mesh.nodes.math.FloatToIntNode;
import ixdar.geometry.mesh.nodes.math.InputIntNode;
import ixdar.geometry.mesh.nodes.math.InputVectorNode;
import ixdar.geometry.mesh.nodes.math.IntegerMathNode;
import ixdar.geometry.mesh.nodes.math.RandomValueNode;

public class MathNodesTest {

    @Test
    public void booleanMathTruthTables() {
        BooleanMathNode node = new BooleanMathNode();

        assertEquals(true, evalBool(node, true, true, "AND"));
        assertEquals(false, evalBool(node, true, false, "AND"));
        assertEquals(false, evalBool(node, false, false, "OR"));
        assertEquals(true, evalBool(node, true, false, "OR"));
        assertEquals(false, evalBool(node, true, true, "NOT"));
        assertEquals(true, evalBool(node, false, false, "NOT"));
        assertEquals(false, evalBool(node, true, true, "XOR"));
        assertEquals(true, evalBool(node, true, false, "XOR"));
    }

    private static boolean evalBool(BooleanMathNode node, boolean a, boolean b, String op) {
        MapNodeContext ctx = new MapNodeContext(node);
        ctx.setInput("a", a);
        ctx.setInput("b", b);
        ctx.setInput("operation", op);
        node.evaluate(ctx);
        return ctx.getOutput("value", Boolean.class);
    }

    @Test
    public void integerMathModes() {
        IntegerMathNode node = new IntegerMathNode();
        assertEquals(7, evalInt(node, 3, 4, "ADD"));
        assertEquals(-1, evalInt(node, 3, 4, "SUBTRACT"));
        assertEquals(12, evalInt(node, 3, 4, "MUL"));
        assertEquals(2, evalInt(node, 9, 4, "DIVIDE"));
        assertEquals(0, evalInt(node, 9, 0, "DIVIDE"));
        assertEquals(1, evalInt(node, 9, 4, "MOD"));
        assertEquals(8, evalInt(node, 2, 3, "POWER"));
        assertEquals(2, evalInt(node, 2, 9, "MIN"));
        assertEquals(9, evalInt(node, 2, 9, "MAX"));
    }

    private static int evalInt(IntegerMathNode node, int a, int b, String op) {
        MapNodeContext ctx = new MapNodeContext(node);
        ctx.setInput("a", a);
        ctx.setInput("b", b);
        ctx.setInput("operation", op);
        node.evaluate(ctx);
        return ctx.getOutput("result", Integer.class);
    }

    @Test
    public void floatToIntModes() {
        FloatToIntNode node = new FloatToIntNode();
        assertEquals(3, evalFloatToInt(node, 2.7f, "ROUND"));
        assertEquals(2, evalFloatToInt(node, 2.7f, "FLOOR"));
        assertEquals(3, evalFloatToInt(node, 2.2f, "CEIL"));
        assertEquals(2, evalFloatToInt(node, 2.7f, "TRUNCATE"));
        assertEquals(2, evalFloatToInt(node, 2.7f, "TRUNC"));
        assertEquals(-2, evalFloatToInt(node, -2.7f, "TRUNCATE"));
    }

    private static int evalFloatToInt(FloatToIntNode node, float v, String mode) {
        MapNodeContext ctx = new MapNodeContext(node);
        ctx.setInput("value", v);
        ctx.setInput("mode", mode);
        node.evaluate(ctx);
        return ctx.getOutput("result", Integer.class);
    }

    @Test
    public void compareWithEpsilon() {
        CompareNode node = new CompareNode();
        MapNodeContext ctx = new MapNodeContext(node);
        ctx.setInput("a", 1.0f);
        ctx.setInput("b", 1.0000005f);
        ctx.setInput("epsilon", 1e-6f);
        ctx.setInput("mode", "EQUAL");
        node.evaluate(ctx);
        assertTrue(ctx.getOutput("value", Boolean.class));

        ctx.setInput("a", 1.0f);
        ctx.setInput("b", 1.01f);
        ctx.setInput("epsilon", 1e-6f);
        ctx.setInput("mode", "LESS");
        node.evaluate(ctx);
        assertTrue(ctx.getOutput("value", Boolean.class));

        ctx.setInput("a", 1.0f);
        ctx.setInput("b", 1.0000005f);
        ctx.setInput("epsilon", 1e-6f);
        ctx.setInput("mode", "LESS");
        node.evaluate(ctx);
        assertFalse(ctx.getOutput("value", Boolean.class));

        ctx.setInput("a", 0.5f);
        ctx.setInput("b", 1.0f);
        ctx.setInput("epsilon", 1e-6f);
        ctx.setInput("mode", "LT");
        node.evaluate(ctx);
        assertTrue(ctx.getOutput("value", Boolean.class));
    }

    @Test
    public void randomValueDeterminism() {
        RandomValueNode node = new RandomValueNode();
        float a = evalRandomFloat(node, 42, 0f, 10f);
        float b = evalRandomFloat(node, 42, 0f, 10f);
        assertEquals(a, b, 0f);

        MapNodeContext ctx = new MapNodeContext(node);
        ctx.setInput("seed", 7);
        ctx.setInput("min", 0f);
        ctx.setInput("max", 1f);
        ctx.setInput("mode", "VECTOR");
        node.evaluate(ctx);
        Vector3Value v1 = ctx.getOutput("vector", Vector3Value.class);
        node.evaluate(ctx);
        Vector3Value v2 = ctx.getOutput("vector", Vector3Value.class);
        assertEquals(v1.x(), v2.x(), 0f);
        assertEquals(v1.y(), v2.y(), 0f);
        assertEquals(v1.z(), v2.z(), 0f);
    }

    private static float evalRandomFloat(RandomValueNode node, int seed, float min, float max) {
        MapNodeContext ctx = new MapNodeContext(node);
        ctx.setInput("seed", seed);
        ctx.setInput("min", min);
        ctx.setInput("max", max);
        ctx.setInput("mode", "FLOAT");
        node.evaluate(ctx);
        return ctx.getOutput("float_out", Float.class);
    }

    @Test
    public void inputIntParameterClamps() {
        InputIntNode node = new InputIntNode();
        MapNodeContext ctx = new MapNodeContext(node);
        ctx.setInput("name", "test");
        ctx.setInput("default", 5);
        ctx.setInput("min", 0);
        ctx.setInput("max", 4);
        node.evaluate(ctx);
        assertEquals(4, ctx.getOutput("result", Integer.class));
    }

    @Test
    public void inputVectorComponents() {
        InputVectorNode node = new InputVectorNode();
        MapNodeContext ctx = new MapNodeContext(node);
        ctx.setInput("x", 1f);
        ctx.setInput("y", 2f);
        ctx.setInput("z", 3f);
        node.evaluate(ctx);
        Vector3Value v = ctx.getOutput("vector", Vector3Value.class);
        assertEquals(1f, v.x());
        assertEquals(2f, v.y());
        assertEquals(3f, v.z());
    }

    @Test
    public void unknownModeThrowsOnSetInput() {
        BooleanMathNode b = new BooleanMathNode();
        MapNodeContext ctx = new MapNodeContext(b);
        ctx.setInput("a", true);
        ctx.setInput("b", false);
        assertThrows(IllegalArgumentException.class, () -> ctx.setInput("operation", "NAND"));
    }

    @Test
    public void integerMathModeAliasSubNormalizesToSubtract() {
        IntegerMathNode node = new IntegerMathNode();
        assertEquals(-1, evalInt(node, 3, 4, "SUB"));
    }

    @Test
    public void meshNodeCatalogJsonIncludesModesForBooleanMath() {
        String json = MeshNodeCatalog.toJsonFromAnnotationRegistry();
        assertTrue(json.contains("boolean_math"));
        assertTrue(json.contains("canonicalModes"));
        assertTrue(json.contains("AND"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void meshNodeRegistryContainsMathNodeSuppliers() throws Exception {
        Class<?> registryClass = Class.forName("ixdar.annotations.meshnode.MeshNodeRegistry_MeshNodes");
        Field mapField = registryClass.getField("MAP");
        Map<String, Supplier<? extends MeshNode>> map = (Map<String, Supplier<? extends MeshNode>>) mapField.get(null);

        assertInstanceOf(BooleanMathNode.class, map.get("boolean_math").get());
        assertInstanceOf(IntegerMathNode.class, map.get("integer_math").get());
        assertInstanceOf(RandomValueNode.class, map.get("random_value").get());
    }
}
