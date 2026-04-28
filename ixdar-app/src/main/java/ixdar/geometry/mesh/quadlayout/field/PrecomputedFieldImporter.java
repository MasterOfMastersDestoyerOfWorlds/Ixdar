package ixdar.geometry.mesh.quadlayout.field;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.data.MeshLoader;
import ixdar.geometry.mesh.quadlayout.vectorfield.CombedField;
import ixdar.geometry.mesh.quadlayout.vectorfield.FaceRosyField;
import ixdar.geometry.mesh.quadlayout.vectorfield.Singularity;

/**
 * PATCH-51: load metriko's known-good stage1 outputs and synthesize the
 * Ixdar {@link FaceRosyField} + {@link CombedField} + {@link Singularity}
 * triple needed by the downstream pipeline (PATCH-40 IGM, PATCH-41 T-mesh).
 * Lets us validate the downstream pipeline using a clean upstream while
 * PATCH-50 reworks the Ixdar cross-field solver.
 *
 * <h3>File formats (sniffed from the Hand baseline)</h3>
 * <ul>
 *   <li>{@code stage1_extrinsic_field.tsv} — F lines, 12 tab-separated
 *       doubles per line: four 3-vectors representing the 4-RoSy directions
 *       in the face's tangent plane. We use the FIRST 3-vector (cols 0..2)
 *       as the principal cross direction and project it into the face's
 *       Ixdar local frame to recover {@code theta} per face.</li>
 *   <li>{@code stage1_matching.txt} — E_metriko lines (= mesh.nE in metriko's
 *       igl-style edge enumeration), each an integer in {0,1,2,3}.</li>
 *   <li>{@code stage1_seam.txt} — E_metriko lines, each {0,1}.</li>
 *   <li>{@code stage1_singular.txt} — V lines (= mesh.nV), each line is the
 *       integer index4 (signed; sums to 4*chi) for the corresponding vertex.
 *       Most lines are 0; only singular vertices carry non-zero indices.</li>
 * </ul>
 *
 * <h3>Edge mapping</h3>
 * Metriko ids edges via {@code igl::edge_topology} which produces the
 * lex-sorted unique list of {@code (min(v0,v1), max(v0,v1))} face-edges. We
 * recompute that ordering in {@link #buildIglEdgeIndex} from the OBJ-loaded
 * face indices and cross-reference against Ixdar's interior-edge enumeration
 * via the (sorted) endpoint pair.
 */
public final class PrecomputedFieldImporter {

    public record Result(ArrayMesh mesh,
                         FaceRosyField field,
                         CombedField combed,
                         List<Singularity> singularities) {}

    private PrecomputedFieldImporter() {}

    /**
     * Load the four stage1 diagnostic files plus the OBJ. Each file path is
     * absolute or resolvable from the current working directory.
     *
     * @param objPath          input mesh OBJ
     * @param extrinsicTsv     {@code stage1_extrinsic_field.tsv}
     * @param matchingTxt      {@code stage1_matching.txt}
     * @param seamTxt          {@code stage1_seam.txt}
     * @param singularTxt      {@code stage1_singular.txt}
     */
    public static Result load(Path objPath,
                              Path extrinsicTsv,
                              Path matchingTxt,
                              Path seamTxt,
                              Path singularTxt) throws IOException {
        ArrayMesh mesh = MeshLoader.load(objPath.toString());
        int F = mesh.faceCount();
        int V = mesh.vertexCount();

        double[] theta = readTheta(extrinsicTsv, mesh);
        int[][] iglEdgeVerts = buildIglEdgeIndex(mesh);
        int E_metriko = iglEdgeVerts.length;

        int[] metrikoMatching = readIntLines(matchingTxt, E_metriko);
        int[] metrikoSeamRaw = readIntLines(seamTxt, E_metriko);

        // Map (min_v, max_v) -> metriko edge index for fast lookup.
        HashMap<Long, Integer> pairToMetrikoEdge = new HashMap<>(E_metriko * 2);
        for (int e = 0; e < E_metriko; e++) {
            pairToMetrikoEdge.put(packPair(iglEdgeVerts[e][0], iglEdgeVerts[e][1]), e);
        }

        // Build a temporary FaceRosyField just to learn the interior-edge
        // structure (count, mesh-edge ids). We then translate metriko's
        // per-edge values into Ixdar's interior-edge order.
        FaceRosyField scratch = new FaceRosyField(mesh);
        int E_int = scratch.interiorEdgeCount();
        int[] periodJump = new int[E_int];
        int[] matching = new int[E_int];
        boolean[] seam = new boolean[E_int];
        int unmapped = 0;
        for (int e = 0; e < E_int; e++) {
            int meshEid = scratch.edgeMeshId(e);
            int he = mesh.edgeHalfEdge(meshEid);
            int v0 = mesh.halfEdgeVertex(he);
            int v1 = mesh.halfEdgeEndVertex(he);
            int va = Math.min(v0, v1);
            int vb = Math.max(v0, v1);
            Integer me = pairToMetrikoEdge.get(packPair(va, vb));
            if (me == null) {
                unmapped++;
                continue;
            }
            int m = metrikoMatching[me];
            // Normalize into 0..3. metriko stores it modulo N=4.
            int m4 = ((m % 4) + 4) % 4;
            matching[e] = m4;
            periodJump[e] = m4;
            seam[e] = metrikoSeamRaw[me] != 0;
        }
        if (unmapped != 0) {
            throw new IllegalStateException("PrecomputedFieldImporter: " + unmapped
                    + " of " + E_int + " interior edges did not match a metriko edge");
        }

        FaceRosyField field = FaceRosyField.fromExternal(mesh, theta, periodJump);
        // branch = 0 everywhere: combedAngle(f) = theta(f) directly, which is
        // what we want since theta was extracted from metriko's combed-field
        // first cardinal direction.
        int[] branch = new int[F];
        CombedField combed = CombedField.fromExternal(field, branch, matching, seam);

        List<Singularity> singularities = readSingularities(singularTxt, V);
        return new Result(mesh, field, combed, singularities);
    }

    /**
     * Read per-face theta from the 12-column extrinsic TSV. Cols 0..2 are
     * the first cross-field cardinal direction in 3D world space; we project
     * that into the face's Ixdar local frame
     * {@code (frameU, frameV)} via theta = atan2(d.v, d.u) so the resulting
     * angle drives {@code BaseField.directionAt} to reproduce the original
     * 3D vector.
     */
    private static double[] readTheta(Path tsv, ArrayMesh mesh) throws IOException {
        int F = mesh.faceCount();
        double[] theta = new double[F];
        Vector3f u = new Vector3f();
        Vector3f v = new Vector3f();
        Vector3f n = new Vector3f();
        Vector3f d = new Vector3f();
        // Local copy of frame(u,v) — BaseField stores them, we read via the
        // directionAt helpers but those don't expose without a Vector3f scratch.
        // FaceRosyField inherits from BaseField; we can use directionAt's frame
        // accessors which are public. Reach them via a temporary BaseField
        // subclass would be heavy; instead recompute the local frame the same
        // way BaseField does.
        Vector3f p0 = new Vector3f();
        Vector3f p1 = new Vector3f();
        Vector3f p2 = new Vector3f();
        Vector3f e0 = new Vector3f();
        Vector3f e1 = new Vector3f();
        try (BufferedReader r = new BufferedReader(new FileReader(tsv.toFile()))) {
            String line;
            int row = 0;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) continue;
                String[] parts = line.split("\\s+");
                if (parts.length < 3) {
                    throw new IOException("extrinsic field row " + row + " has only "
                            + parts.length + " columns");
                }
                if (row >= F) {
                    throw new IOException("extrinsic field has more rows than faces ("
                            + F + ")");
                }
                d.set(Float.parseFloat(parts[0]),
                      Float.parseFloat(parts[1]),
                      Float.parseFloat(parts[2]));

                mesh.vertexPosition(mesh.faceVertexAt(row, 0), p0);
                mesh.vertexPosition(mesh.faceVertexAt(row, 1), p1);
                mesh.vertexPosition(mesh.faceVertexAt(row, 2), p2);
                e0.set(p1).sub(p0);
                e1.set(p2).sub(p0);
                e0.cross(e1, n);
                float nl = n.length();
                if (nl > 1e-30f) n.mul(1f / nl); else n.set(0, 0, 1);
                float el = e0.length();
                if (el > 1e-30f) e0.mul(1f / el); else e0.set(1, 0, 0);
                n.cross(e0, v);
                u.set(e0);

                // Project d onto tangent plane (drop normal component) then
                // express in (u, v).
                float dn = d.dot(n);
                d.sub(n.x * dn, n.y * dn, n.z * dn);
                double du = d.dot(u);
                double dv = d.dot(v);
                theta[row] = Math.atan2(dv, du);
                row++;
            }
            if (row != F) {
                throw new IOException("extrinsic field rows " + row + " != faceCount " + F);
            }
        }
        return theta;
    }

    /**
     * Replicate {@code igl::edge_topology}'s edge enumeration: for each face
     * push (min(v0,v1), max(v0,v1)) for all 3 face-edges, sort the
     * resulting 3F entries lex by (a, b), then dedup consecutive equal pairs.
     * The metriko edge id is the position in the deduped list.
     */
    static int[][] buildIglEdgeIndex(ArrayMesh mesh) {
        int F = mesh.faceCount();
        long[] keys = new long[F * 3];
        int idx = 0;
        for (int f = 0; f < F; f++) {
            int v0 = mesh.faceVertexAt(f, 0);
            int v1 = mesh.faceVertexAt(f, 1);
            int v2 = mesh.faceVertexAt(f, 2);
            keys[idx++] = packPair(Math.min(v0, v1), Math.max(v0, v1));
            keys[idx++] = packPair(Math.min(v1, v2), Math.max(v1, v2));
            keys[idx++] = packPair(Math.min(v2, v0), Math.max(v2, v0));
        }
        Arrays.sort(keys);
        int unique = 0;
        for (int i = 0; i < keys.length; i++) {
            if (i == 0 || keys[i] != keys[i - 1]) unique++;
        }
        int[][] out = new int[unique][2];
        int w = 0;
        for (int i = 0; i < keys.length; i++) {
            if (i == 0 || keys[i] != keys[i - 1]) {
                out[w][0] = (int) (keys[i] >>> 32);
                out[w][1] = (int) (keys[i] & 0xffffffffL);
                w++;
            }
        }
        return out;
    }

    private static long packPair(int a, int b) {
        return (((long) a) << 32) | (b & 0xffffffffL);
    }

    private static int[] readIntLines(Path p, int expected) throws IOException {
        int[] out = new int[expected];
        try (BufferedReader r = new BufferedReader(new FileReader(p.toFile()))) {
            String line;
            int i = 0;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if (i >= expected) {
                    throw new IOException(p + " has more rows than expected ("
                            + expected + ")");
                }
                out[i++] = Integer.parseInt(line);
            }
            if (i != expected) {
                throw new IOException(p + " row count " + i + " != expected " + expected);
            }
        }
        return out;
    }

    private static List<Singularity> readSingularities(Path p, int vertexCount) throws IOException {
        ArrayList<Singularity> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader(p.toFile()))) {
            String line;
            int v = 0;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if (v >= vertexCount) {
                    throw new IOException(p + " has more rows than vertexCount " + vertexCount);
                }
                int idx4 = Integer.parseInt(line);
                if (idx4 != 0) {
                    out.add(new Singularity(v, idx4));
                }
                v++;
            }
            if (v != vertexCount) {
                throw new IOException(p + " row count " + v + " != vertexCount " + vertexCount);
            }
        }
        return out;
    }
}
