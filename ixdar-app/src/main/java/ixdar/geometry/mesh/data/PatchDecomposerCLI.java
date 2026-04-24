package ixdar.geometry.mesh.data;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Map;

import javax.imageio.ImageIO;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Headless CLI for the patch decomposition pipeline. Lets Python invoke the
 * Java geometry work during reference-mesh preprocessing without needing the
 * full game + automation server running.
 *
 * <pre>
 *   decompose &lt;obj_path&gt; [resolution]
 *   render &lt;obj_path&gt; &lt;out_png&gt; [resolution]
 *   segment &lt;method&gt; &lt;obj_path&gt; [n_clusters]
 * </pre>
 *
 * All commands print a single JSON object to stdout. Errors go to stderr and
 * the process exits non-zero.
 */
public final class PatchDecomposerCLI {

    private PatchDecomposerCLI() {}

    public static void main(String[] args) {
        if (args.length < 1) {
            usage();
            System.exit(2);
        }
        try {
            switch (args[0]) {
                case "decompose" -> decompose(args);
                case "render" -> render(args);
                case "render-flat" -> renderFlat(args);
                case "segment" -> segment(args);
                case "stats" -> stats(args);
                case "crest-lines" -> crestLines(args);
                default -> {
                    System.err.println("Unknown command: " + args[0]);
                    usage();
                    System.exit(2);
                }
            }
        } catch (Throwable t) {
            JsonObject err = new JsonObject();
            err.addProperty("ok", false);
            err.addProperty("error", t.getClass().getSimpleName() + ": " + t.getMessage());
            System.out.println(err);
            System.exit(1);
        }
    }

    private static void decompose(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("decompose requires <obj_path>");
        String path = args[1];
        int resolution = args.length > 2 ? Integer.parseInt(args[2]) : 128;
        ArrayMesh mesh = MeshLoader.load(path);
        PatchDecomposition d = SemanticPatchDecomposer.decompose(mesh, resolution);
        System.out.println(decompositionToJson(d));
    }

    private static void render(String[] args) throws Exception {
        if (args.length < 3) throw new IllegalArgumentException("render requires <obj_path> <out_png>");
        String path = args[1];
        String outPath = args[2];
        int resolution = args.length > 3 ? Integer.parseInt(args[3]) : 128;
        ArrayMesh mesh = MeshLoader.load(path);
        PatchDecomposition d = SemanticPatchDecomposer.decompose(mesh, resolution);
        BufferedImage img = PatchRenderer.renderMultiview(mesh, d);
        File out = new File(outPath);
        if (!out.isAbsolute()) out = new File(System.getProperty("user.dir"), outPath);
        File parent = out.getParentFile();
        if (parent != null) parent.mkdirs();
        ImageIO.write(img, "PNG", out);
        JsonObject res = new JsonObject();
        res.addProperty("ok", true);
        res.addProperty("path", out.getAbsolutePath());
        res.addProperty("width", img.getWidth());
        res.addProperty("height", img.getHeight());
        res.addProperty("patch_count", d.patches().size());
        // Emit per-patch palette so the Python caller can build a colored-legend
        // prompt without re-running decompose.
        JsonArray palette = new JsonArray();
        for (Patch p : d.patches()) {
            JsonObject pj = new JsonObject();
            pj.addProperty("id", p.id());
            pj.addProperty("color", p.color());
            pj.addProperty("vertex_count", p.vertexIndices().length);
            palette.add(pj);
        }
        res.add("palette", palette);
        System.out.println(res);
    }

    private static void renderFlat(String[] args) throws Exception {
        if (args.length < 3) throw new IllegalArgumentException("render-flat requires <obj_path> <out_png>");
        String path = args[1];
        String outPath = args[2];
        int resolution = args.length > 3 ? Integer.parseInt(args[3]) : 128;
        ArrayMesh mesh = MeshLoader.load(path);
        PatchDecomposition d = SemanticPatchDecomposer.decompose(mesh, resolution);
        BufferedImage img = PatchRenderer.renderMultiviewFlat(mesh, d);
        File out = new File(outPath);
        if (!out.isAbsolute()) out = new File(System.getProperty("user.dir"), outPath);
        File parent = out.getParentFile();
        if (parent != null) parent.mkdirs();
        ImageIO.write(img, "PNG", out);
        JsonObject res = new JsonObject();
        res.addProperty("ok", true);
        res.addProperty("path", out.getAbsolutePath());
        res.addProperty("width", img.getWidth());
        res.addProperty("height", img.getHeight());
        res.addProperty("patch_count", d.patches().size());
        // Per-patch globally-unique flat colour so the Python side can build
        // (hex -> patch_id) and (hex -> patch_name) lookups without re-running
        // the decomposition.
        JsonArray palette = new JsonArray();
        for (Patch p : d.patches()) {
            JsonObject pj = new JsonObject();
            pj.addProperty("id", p.id());
            pj.addProperty("flat_color", PatchRenderer.uniquePatchColorHex(p.id()));
            pj.addProperty("vertex_count", p.vertexIndices().length);
            palette.add(pj);
        }
        res.add("palette", palette);
        System.out.println(res);
    }

    private static void segment(String[] args) throws Exception {
        if (args.length < 3) throw new IllegalArgumentException("segment requires <method> <obj_path>");
        String method = args[1];
        String path = args[2];
        int nClusters = args.length > 3 ? Integer.parseInt(args[3]) : 6;
        ArrayMesh mesh = MeshLoader.load(path);
        Map<String, int[]> tags = switch (method) {
            case "components" -> MeshSegmenter.segmentComponents(mesh);
            case "curvature" -> MeshSegmenter.segmentCurvature(mesh, nClusters);
            case "spatial" -> MeshSegmenter.segmentSpatial(mesh, nClusters);
            default -> throw new IllegalArgumentException("Unknown method: " + method);
        };
        JsonObject res = new JsonObject();
        res.addProperty("ok", true);
        res.addProperty("vertex_count", mesh.vertexCount());
        res.addProperty("method", method);
        JsonObject tagsJson = new JsonObject();
        for (Map.Entry<String, int[]> e : tags.entrySet()) {
            JsonArray arr = new JsonArray();
            for (int v : e.getValue()) arr.add(v);
            tagsJson.add(e.getKey(), arr);
        }
        res.add("tags", tagsJson);
        System.out.println(res);
    }

    static JsonObject decompositionToJson(PatchDecomposition d) {
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("vertex_count", d.vertexCount());
        JsonArray patches = new JsonArray();
        for (Patch p : d.patches()) {
            JsonObject pj = new JsonObject();
            pj.addProperty("id", p.id());
            pj.addProperty("branch_id", p.branchId());
            pj.addProperty("color", p.color());
            pj.addProperty("curvature_mean", p.curvatureMean());
            JsonArray centroid = new JsonArray();
            for (float c : p.centroid()) centroid.add(c);
            pj.add("centroid", centroid);
            JsonArray verts = new JsonArray();
            for (int v : p.vertexIndices()) verts.add(v);
            pj.add("vertex_indices", verts);
            JsonArray faces = new JsonArray();
            for (int fi : p.faceIndices()) faces.add(fi);
            pj.add("face_indices", faces);
            patches.add(pj);
        }
        out.add("patches", patches);
        return out;
    }

    private static void stats(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("stats requires <obj_path>");
        ArrayMesh mesh = MeshLoader.load(args[1]);
        SemanticPatchDecomposer.EdgeDihedrals ed = SemanticPatchDecomposer.computeEdgeDihedrals(mesh);
        int n = ed.dihedralByEdge().size();
        float[] ds = new float[n];
        int i = 0;
        for (Float f : ed.dihedralByEdge().values()) ds[i++] = f;
        java.util.Arrays.sort(ds);
        JsonObject res = new JsonObject();
        res.addProperty("ok", true);
        res.addProperty("vertex_count", mesh.vertexCount());
        res.addProperty("edge_count", n);
        res.addProperty("dihedral_min_rad", ds[0]);
        res.addProperty("dihedral_max_rad", ds[n - 1]);
        int[] pcts = {10, 25, 50, 75, 90, 95, 99};
        JsonObject pct = new JsonObject();
        for (int p : pcts) {
            pct.addProperty("p" + p, ds[Math.min(n - 1, (int) (n * (p / 100.0)))]);
        }
        res.add("dihedral_percentiles_rad", pct);
        // Angle-defect: approximate per-vertex Gaussian curvature.
        float[] defect = new float[mesh.vertexCount()];
        java.util.Arrays.fill(defect, (float) (2 * Math.PI));
        int[] faceIdx = mesh.copyFaceIndices();
        float[] positions = mesh.copyPositions();
        int faceCount = faceIdx.length / 3;
        for (int f = 0; f < faceCount; f++) {
            int v0 = faceIdx[f * 3];
            int v1 = faceIdx[f * 3 + 1];
            int v2 = faceIdx[f * 3 + 2];
            defect[v0] -= triAngle(positions, v0, v1, v2);
            defect[v1] -= triAngle(positions, v1, v2, v0);
            defect[v2] -= triAngle(positions, v2, v0, v1);
        }
        float[] dd = defect.clone();
        java.util.Arrays.sort(dd);
        JsonObject defectPct = new JsonObject();
        for (int p : pcts) {
            defectPct.addProperty("p" + p, dd[Math.min(dd.length - 1, (int) (dd.length * (p / 100.0)))]);
        }
        res.addProperty("angle_defect_min", dd[0]);
        res.addProperty("angle_defect_max", dd[dd.length - 1]);
        res.add("angle_defect_percentiles", defectPct);
        System.out.println(res);
    }

    private static float triAngle(float[] p, int at, int b, int c) {
        float ax = p[b * 3] - p[at * 3];
        float ay = p[b * 3 + 1] - p[at * 3 + 1];
        float az = p[b * 3 + 2] - p[at * 3 + 2];
        float bx = p[c * 3] - p[at * 3];
        float by = p[c * 3 + 1] - p[at * 3 + 1];
        float bz = p[c * 3 + 2] - p[at * 3 + 2];
        float la = (float) Math.sqrt(ax * ax + ay * ay + az * az);
        float lb = (float) Math.sqrt(bx * bx + by * by + bz * bz);
        if (la < 1e-20f || lb < 1e-20f) return 0f;
        float dot = (ax * bx + ay * by + az * bz) / (la * lb);
        dot = Math.max(-1f, Math.min(1f, dot));
        return (float) Math.acos(dot);
    }

    /**
     * PATCH-11: emit the detected crest lines as an OBJ with {@code l}
     * line primitives so we can overlay them on the source mesh in Blender
     * and sanity-check ridge / valley coverage.
     */
    private static void crestLines(String[] args) throws Exception {
        if (args.length < 3) throw new IllegalArgumentException("crest-lines requires <obj_path> <out_obj>");
        String path = args[1];
        String outPath = args[2];
        ArrayMesh mesh = MeshLoader.load(path);
        SemanticPatchDecomposer.EdgeDihedrals ed = SemanticPatchDecomposer.computeEdgeDihedrals(mesh);
        PrincipalDirectionField pdf = PrincipalDirectionField.compute(mesh, ed);
        CrestLineDetector.CrestLines crest = CrestLineDetector.detect(mesh, ed, pdf);

        File out = new File(outPath);
        if (!out.isAbsolute()) out = new File(System.getProperty("user.dir"), outPath);
        File parent = out.getParentFile();
        if (parent != null) parent.mkdirs();
        try (java.io.BufferedWriter w = java.nio.file.Files.newBufferedWriter(out.toPath())) {
            w.write("# Crest lines for " + path + "\n");
            w.write("# ridge polylines: " + crest.ridgePolylines.size()
                    + "  valley polylines: " + crest.valleyPolylines.size() + "\n");
            w.write("# object 'ridges' then 'valleys'\n");
            // Vertex positions from the source mesh (indices preserved).
            float[] positions = mesh.copyPositions();
            for (int i = 0; i < positions.length; i += 3) {
                w.write("v " + positions[i] + " " + positions[i + 1] + " " + positions[i + 2] + "\n");
            }
            w.write("o ridges\n");
            writePolylines(w, crest.ridgePolylines);
            w.write("o valleys\n");
            writePolylines(w, crest.valleyPolylines);
        }

        JsonObject res = new JsonObject();
        res.addProperty("ok", true);
        res.addProperty("path", out.getAbsolutePath());
        res.addProperty("ridge_polylines", crest.ridgePolylines.size());
        res.addProperty("valley_polylines", crest.valleyPolylines.size());
        res.addProperty("ridge_points", crest.ridgePolylines.stream().mapToInt(a -> a.length).sum());
        res.addProperty("valley_points", crest.valleyPolylines.stream().mapToInt(a -> a.length).sum());
        res.addProperty("crest_edges", crest.crestEdges.size());
        System.out.println(res);
    }

    private static void writePolylines(java.io.BufferedWriter w, java.util.List<int[]> lines) throws Exception {
        for (int[] line : lines) {
            if (line.length < 2) continue;
            // OBJ line primitives are 1-indexed.
            StringBuilder sb = new StringBuilder("l");
            for (int v : line) sb.append(" ").append(v + 1);
            sb.append("\n");
            w.write(sb.toString());
        }
    }

    private static void usage() {
        System.err.println("Usage:");
        System.err.println("  decompose <obj_path> [resolution]");
        System.err.println("  render <obj_path> <out_png> [resolution]");
        System.err.println("  render-flat <obj_path> <out_png> [resolution]");
        System.err.println("  segment <method> <obj_path> [n_clusters]");
        System.err.println("  stats <obj_path>");
        System.err.println("  crest-lines <obj_path> <out_obj>");
    }
}
