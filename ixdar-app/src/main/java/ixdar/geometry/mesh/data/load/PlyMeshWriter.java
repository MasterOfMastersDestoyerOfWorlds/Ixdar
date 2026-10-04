package ixdar.geometry.mesh.data.load;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import ixdar.geometry.mesh.data.GeometryBundle;

/**
 * Writes a bundle as ASCII PLY for the tools that want text: the mesh's own vertices with normals,
 * and triangles carrying MeshLab's per-face {@code texcoord} list when the bundle has corner UVs.
 * Byte-stable, since the text follows from the geometry alone.
 */
public final class PlyMeshWriter {

    public static final int VERTEX_LINE_BYTES_ESTIMATE = 96;

    public static final int FACE_LINE_BYTES_ESTIMATE = 24;

    private PlyMeshWriter() {
    }

    /**
     * Write {@code bundle} as a {@code .ply}, creating the parent directories.
     *
     * @param bundle geometry to write
     * @param file destination path
     * @return the flattened export that was written, for the caller's own report
     * @throws IOException if the bundle holds no triangles or the file cannot be written
     */
    public static MeshExport write(GeometryBundle bundle, Path file) throws IOException {
        MeshExport geometry = new MeshExport(bundle);
        geometry.splitSeams = false;
        geometry.build();
        byte[] bytes = ply(geometry);
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(file, bytes);
        return geometry;
    }

    /**
     * Serialize a flattened export as ASCII PLY text.
     *
     * @param geometry a built {@link MeshExport}
     * @return the file's bytes, UTF-8 with LF line endings
     * @throws IOException if the export holds no triangles
     */
    public static byte[] ply(MeshExport geometry) throws IOException {
        if (geometry.triangleCount() == 0) {
            throw new IOException("PLY export needs at least one triangle");
        }
        boolean hasUv = geometry.cornerU != null;
        StringBuilder text = new StringBuilder(geometry.vertexCount() * VERTEX_LINE_BYTES_ESTIMATE
                + geometry.triangleCount() * FACE_LINE_BYTES_ESTIMATE);
        text.append("ply\nformat ascii 1.0\ncomment ").append(GltfMeshWriter.ASSET_GENERATOR)
                .append("\nelement vertex ").append(geometry.vertexCount()).append('\n');
        property(text, "x");
        property(text, "y");
        property(text, "z");
        property(text, "nx");
        property(text, "ny");
        property(text, "nz");
        text.append("element face ").append(geometry.triangleCount())
                .append("\nproperty list uchar int vertex_indices\n");
        if (hasUv) {
            text.append("property list uchar float texcoord\n");
        }
        text.append("end_header\n");
        for (int vertex = 0; vertex < geometry.vertexCount(); vertex++) {
            int offset = vertex * MeshExport.FLOATS_PER_VERTEX;
            text.append(geometry.positions[offset]).append(' ')
                    .append(geometry.positions[offset + 1]).append(' ')
                    .append(geometry.positions[offset + 2]).append(' ')
                    .append(geometry.normals[offset]).append(' ')
                    .append(geometry.normals[offset + 1]).append(' ')
                    .append(geometry.normals[offset + 2]).append('\n');
        }
        for (int triangle = 0; triangle < geometry.triangleCount(); triangle++) {
            int first = triangle * MeshExport.CORNERS_PER_TRIANGLE;
            text.append(MeshExport.CORNERS_PER_TRIANGLE);
            for (int corner = first; corner < first + MeshExport.CORNERS_PER_TRIANGLE; corner++) {
                text.append(' ').append(geometry.triangleIndices[corner]);
            }
            if (hasUv) {
                text.append(' ').append(MeshExport.CORNERS_PER_TRIANGLE * MeshExport.UV_COMPONENTS);
                for (int corner = first; corner < first + MeshExport.CORNERS_PER_TRIANGLE; corner++) {
                    text.append(' ').append((float) geometry.cornerU[corner])
                            .append(' ').append((float) geometry.cornerV[corner]);
                }
            }
            text.append('\n');
        }
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Append one scalar float property declaration to the header.
     *
     * @param text header being built
     * @param name the property's name
     */
    private static void property(StringBuilder text, String name) {
        text.append("property float ").append(name).append('\n');
    }
}
