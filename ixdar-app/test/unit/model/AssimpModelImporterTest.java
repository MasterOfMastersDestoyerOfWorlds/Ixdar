package unit.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ixdar.graphics.render.model.AssimpModelImporter;
import ixdar.graphics.render.model.ImportedModelData;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class AssimpModelImporterTest {

    private final AssimpModelImporter importer = new AssimpModelImporter();

    @Test
    public void importsSimpleObjWithExpectedVertexAndIndexLayout() throws Exception {
        Path obj = Files.createTempFile("ixdar-simple-", ".obj");
        Files.writeString(obj, simpleTriangleObj());

        ImportedModelData data = importer.importFromFile(obj.toAbsolutePath().toString());

        assertEquals(3, data.vertexCount);
        assertEquals(3, data.indices.length);
        assertEquals(24, data.vertices.length); // 3 vertices * (3 pos + 3 normal + 2 uv)
        assertTrue(data.hasTexCoords);
        assertTrue(data.radius > 0f);
        assertFalse(Float.isNaN(data.center.x));
        assertFalse(Float.isNaN(data.center.y));
        assertFalse(Float.isNaN(data.center.z));

        for (int index : data.indices) {
            assertTrue(index >= 0 && index < data.vertexCount, "Index out of bounds: " + index);
        }
    }

    @Test
    public void importFailsForObjWithoutRenderableGeometry() throws Exception {
        Path obj = Files.createTempFile("ixdar-empty-", ".obj");
        Files.writeString(obj, "o Empty\n# No vertices or faces\n");

        assertThrows(IOException.class, () -> importer.importFromFile(obj.toAbsolutePath().toString()));
    }

    @Test
    public void syntheticMeshImportsWithinReasonableTime() throws Exception {
        Path obj = Files.createTempFile("ixdar-synth-", ".obj");
        Files.writeString(obj, syntheticObj(1000));

        long startNanos = System.nanoTime();
        ImportedModelData data = importer.importFromFile(obj.toAbsolutePath().toString());
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;

        assertEquals(3000, data.vertexCount);
        assertEquals(3000, data.indices.length);
        assertTrue(elapsedMillis < 5000, "Import was unexpectedly slow: " + elapsedMillis + "ms");
    }

    private static String simpleTriangleObj() {
        return ""
                + "o Triangle\n"
                + "v 0.0 0.0 0.0\n"
                + "v 1.0 0.0 0.0\n"
                + "v 0.0 1.0 0.0\n"
                + "vt 0.0 0.0\n"
                + "vt 1.0 0.0\n"
                + "vt 0.0 1.0\n"
                + "vn 0.0 0.0 1.0\n"
                + "f 1/1/1 2/2/1 3/3/1\n";
    }

    private static String syntheticObj(int triangleCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("o Synthetic\n");
        for (int i = 0; i < triangleCount; i++) {
            float x = i * 0.01f;
            sb.append("v ").append(x).append(" 0.0 0.0\n");
            sb.append("v ").append(x).append(" 1.0 0.0\n");
            sb.append("v ").append(x + 0.5f).append(" 0.5 0.0\n");
        }
        sb.append("vn 0.0 0.0 1.0\n");
        for (int i = 0; i < triangleCount; i++) {
            int base = (i * 3) + 1;
            sb.append("f ").append(base).append("//1 ")
                    .append(base + 1).append("//1 ")
                    .append(base + 2).append("//1\n");
        }
        return sb.toString();
    }
}
