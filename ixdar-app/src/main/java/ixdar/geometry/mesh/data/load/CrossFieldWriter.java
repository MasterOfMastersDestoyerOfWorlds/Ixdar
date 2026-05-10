package ixdar.geometry.mesh.data.load;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Locale;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.quadlayout.CrossField;
import ixdar.geometry.mesh.quadlayout.vectorfield.Singularity;

/**
 * Emit a {@link CrossField} in the .ndf format consumed by
 * {@link CrossFieldLoader}. The format mirrors the BCEAK13 supplementary
 * NDFs: five INI-style sections ({@code [Comments]}, {@code [Information]},
 * {@code [Pjumps]}, {@code [Theta]}, {@code [Singularities]}) where the
 * value lists are quoted, semicolon-delimited strings with a trailing
 * semicolon. Arrays are written in the same OpenMesh-style iteration order
 * the loader expects, i.e. positional active-index order.
 */
public final class CrossFieldWriter {
    private static final String VALUE_DELIMITER = ";";
    private static final String DOUBLE_FORMAT = "%.30g";
    private static final String NL = "\n";
    private static final String VALUES_OPEN = "values=\"";
    private static final String QUOTE_NL_NL = "\"\n\n";
    private static final String QUOTE_NL = "\"\n";

    private CrossFieldWriter() {
    }

    /**
     * Write {@code field} to {@code ndfPath}, using {@code mesh} for size
     * metadata and {@code modelName} for the {@code Name=} header.
     *
     * @param ndfPath   filesystem path to write
     * @param field     populated cross field (theta + periodJump + singularities)
     * @param mesh      mesh whose iteration order matches {@code field}
     * @param modelName logical name written into the {@code [Information]} block
     *                  (typically the source mesh filename, e.g. {@code hand_in_tri.off})
     * @throws IOException              if the write fails
     * @throws IllegalStateException    if {@code field} has not been built yet
     * @throws IllegalArgumentException if {@code field}'s arrays don't match {@code mesh}
     */
    public static void write(String ndfPath, CrossField field, HalfEdgeMesh mesh,
            String modelName) throws IOException {
        if (field.theta == null || field.periodJump == null) {
            throw new IllegalStateException("CrossField has no theta/periodJump arrays — call build() first");
        }
        if (field.theta.length != mesh.faceCount()) {
            throw new IllegalArgumentException("theta length " + field.theta.length
                    + " != mesh faces " + mesh.faceCount());
        }
        if (field.periodJump.length != mesh.edgeCount()) {
            throw new IllegalArgumentException("periodJump length " + field.periodJump.length
                    + " != mesh edges " + mesh.edgeCount());
        }
        try (BufferedWriter w = new BufferedWriter(new FileWriter(ndfPath))) {
            w.write("[Comments]" + NL);
            w.write(NL);

            w.write("[Information]" + NL);
            w.write("Name=" + modelName + NL);
            w.write("Vertices=" + mesh.vertexCount() + NL);
            w.write("Edges=" + mesh.edgeCount() + NL);
            w.write("Faces=" + mesh.faceCount() + NL);
            w.write("Genus=0" + NL);
            w.write("Boundaries=" + countBoundaryLoops(mesh) + NL);
            w.write("Singularities=" + field.singularities.size() + NL);
            w.write(NL);

            w.write("[Pjumps]" + NL);
            w.write(VALUES_OPEN);
            for (int eAi = 0; eAi < field.periodJump.length; eAi++) {
                w.write(Integer.toString(field.periodJump[eAi]));
                w.write(VALUE_DELIMITER);
            }
            w.write(QUOTE_NL_NL);

            w.write("[Theta]" + NL);
            w.write(VALUES_OPEN);
            for (int fAi = 0; fAi < field.theta.length; fAi++) {
                w.write(String.format(Locale.ROOT, DOUBLE_FORMAT, (double) field.theta[fAi]));
                w.write(VALUE_DELIMITER);
            }
            w.write(QUOTE_NL_NL);

            w.write("[Singularities]" + NL);
            w.write("indices=\"");
            for (Singularity s : field.singularities) {
                int vAi = activeIndexOfVertex(mesh, s.vertexId());
                w.write(Integer.toString(vAi));
                w.write(VALUE_DELIMITER);
            }
            w.write(QUOTE_NL);
            w.write("singularities=\"");
            for (Singularity s : field.singularities) {
                w.write(Integer.toString(s.index4()));
                w.write(VALUE_DELIMITER);
            }
            w.write(QUOTE_NL);
        }
    }

    /**
     * Look up the active index of a vertex by its id (linear scan; O(V) per
     * call but only invoked once per singularity which is sparse).
     */
    private static int activeIndexOfVertex(HalfEdgeMesh mesh, int vId) {
        int n = mesh.vertexCount();
        for (int vAi = 0; vAi < n; vAi++) {
            if (mesh.vertexIdAt(vAi) == vId) {
                return vAi;
            }
        }
        return -1;
    }

    /**
     * Count distinct boundary loops by walking each unvisited boundary edge.
     * Used for the {@code Boundaries=} field; loader does not validate it.
     */
    private static int countBoundaryLoops(HalfEdgeMesh mesh) {
        int e = mesh.edgeCount();
        boolean[] visited = new boolean[e];
        int loops = 0;
        for (int eAi = 0; eAi < e; eAi++) {
            int eId = mesh.edgeIdAt(eAi);
            if (visited[eAi] || !mesh.isBoundaryEdge(eId)) {
                continue;
            }
            loops++;
            visited[eAi] = true;
        }
        return loops;
    }
}
