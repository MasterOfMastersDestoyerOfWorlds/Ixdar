package ixdar.geometry.mesh.nodes.data;

import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.csg.QuadTriangulation;
import ixdar.geometry.mesh.data.CornerUvField;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.MaterialData;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.nodes.api.InputPort;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;
import ixdar.geometry.mesh.nodes.api.Vector3Value;

/**
 * Gives a mesh triangles, world-space box UVs and a generated two-tone checker image: a textured
 * operand without a scan on disk. Projecting world coordinates onto each face's dominant plane
 * leaves the checker unbroken across any cut, so a break in it is a real interpolation error.
 */
@MeshNodeAnnotation(id = "checker_material")
public class CheckerMaterialNode implements MeshNode {

    public static final int TEXTURE_SIZE = 64;

    public static final int RGBA_BYTES = 4;

    public static final int CHANNEL_MAX = 255;

    public static final float DARK_SQUARE_SCALE = 0.3f;

    public static final float DEFAULT_TILE_SIZE = 0.5f;

    public static final InputPort GEOMETRY =
            new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    public static final InputPort TILE_SIZE =
            new InputPort("tile_size", PortType.FLOAT, DEFAULT_TILE_SIZE, 0.001f, 1000f);
    public static final InputPort COLOR =
            new InputPort("color", PortType.VECTOR3, new Vector3Value(1f, 1f, 1f));
    public static final OutputPort GEOMETRY_OUT =
            new OutputPort(GEOMETRY.name, PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, TILE_SIZE, COLOR);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY_OUT);
    }

    @Override
    public String description() {
        return "Triangulates a mesh and gives it world-space box UVs plus a generated two-tone"
                + " checker base-colour texture, for exercising the textured pipeline.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                GEOMETRY.name, "Mesh to texture; quads are split into triangles, since per-corner"
                        + " UVs cover triangles.",
                TILE_SIZE.name, "World size of one checker square, so the pattern stays the same"
                        + " scale whatever the mesh's size.",
                COLOR.name, "Colour of the light squares; the dark ones are the same colour dimmed."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle base = ctx.getInput(GEOMETRY.name, GeometryBundle.class);
        if (base == null || base.mesh() == null || base.mesh().faceCount() == 0) {
            ctx.setOutput(GEOMETRY.name, base == null ? GeometryBundle.empty() : base);
            return;
        }
        Number tileInput = ctx.getInput(TILE_SIZE.name, Number.class);
        float tileSize = tileInput == null ? DEFAULT_TILE_SIZE : tileInput.floatValue();
        Object colorInput = ctx.getInput(COLOR.name, Object.class);
        Vector3Value color = colorInput instanceof Vector3Value value ? value
                : (Vector3Value) COLOR.defaultValue;

        QuadTriangulation triangulated = new QuadTriangulation(base.mesh()).build();
        ArrayMesh mesh = new ArrayMesh(triangulated.positions,
                new float[triangulated.positions.length], triangulated.triangles,
                CornerUvField.CORNERS_PER_FACE);
        mesh.computeNormals();

        int cornerCount = triangulated.triangles.length;
        double[] cornerU = new double[cornerCount];
        double[] cornerV = new double[cornerCount];
        for (int triangle = 0; triangle < cornerCount / CornerUvField.CORNERS_PER_FACE;
                triangle++) {
            int first = triangle * CornerUvField.CORNERS_PER_FACE;
            int axis = mesh.faceDominantAxis(triangle);
            int axisU = (axis + 1) % CornerUvField.CORNERS_PER_FACE;
            int axisV = (axis + 2) % CornerUvField.CORNERS_PER_FACE;
            for (int corner = 0; corner < CornerUvField.CORNERS_PER_FACE; corner++) {
                int vertex = triangulated.triangles[first + corner]
                        * CornerUvField.CORNERS_PER_FACE;
                cornerU[first + corner] = triangulated.positions[vertex + axisU] / tileSize;
                cornerV[first + corner] = triangulated.positions[vertex + axisV] / tileSize;
            }
        }

        ctx.setOutput(GEOMETRY.name, GeometryBundle.ofMesh(mesh)
                .withSlot(CornerUvField.SLOT, new CornerUvField(cornerU, cornerV))
                .withSlot(MaterialData.SLOT, checkerMaterial(color)));
    }

    /**
     * A two-by-two checker of the colour and a dimmed copy of it, which tiles into an endless
     * checkerboard because textures repeat.
     *
     * @param color colour of the light squares
     * @return the material carrying that image
     */
    private static MaterialData checkerMaterial(Vector3Value color) {
        byte[] pixels = new byte[TEXTURE_SIZE * TEXTURE_SIZE * RGBA_BYTES];
        int half = TEXTURE_SIZE / 2;
        for (int row = 0; row < TEXTURE_SIZE; row++) {
            for (int column = 0; column < TEXTURE_SIZE; column++) {
                boolean light = (row < half) == (column < half);
                float scale = light ? 1f : DARK_SQUARE_SCALE;
                int pixel = (row * TEXTURE_SIZE + column) * RGBA_BYTES;
                pixels[pixel] = channel(color.x() * scale);
                pixels[pixel + 1] = channel(color.y() * scale);
                pixels[pixel + 2] = channel(color.z() * scale);
                pixels[pixel + 3] = (byte) CHANNEL_MAX;
            }
        }
        return new MaterialData(pixels, TEXTURE_SIZE, TEXTURE_SIZE, null, 0, 0,
                new float[] {1f, 1f, 1f, 1f}, 0f, 1f);
    }

    /**
     * One colour component as an RGBA8 byte.
     *
     * @param value component in {@code [0, 1]}, clamped
     * @return the byte to store
     */
    private static byte channel(float value) {
        return (byte) Math.round(Math.max(0f, Math.min(1f, value)) * CHANNEL_MAX);
    }
}
