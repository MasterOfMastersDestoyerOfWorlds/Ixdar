package ixdar.geometry.mesh.data.load;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.quadlayout.CrossField;
import ixdar.geometry.mesh.quadlayout.vectorfield.Singularity;

/**
 * Reads .ndf cross-field files from the BCEAK13 supplementary material into a
 * {@link CrossField}. Populates the solver-output arrays only:
 * {@link CrossField#theta}, {@link CrossField#periodJump},
 * {@link CrossField#singularityIndexQuarter}, {@link CrossField#singularities}.
 * Geometry-derived fields (faceX/Y, kappa, faceIdToActive, edgeIdToActive) are
 * left null; call {@link CrossField#build()} on a sibling instance if those are
 * needed.
 *
 * <p>
 * NDF arrays are positionally indexed in OpenMesh's iteration order. The
 * supplied {@link HalfEdgeMesh} is assumed to be in the same order — typically
 * the mesh built directly from the matching {@code *_in_tri.off}. Mismatched
 * orderings will produce a {@link CrossField} whose values are silently
 * permuted; ordering reconciliation is a separate concern.
 */
public final class CrossFieldLoader {
    private static final String SECTION_INFORMATION = "[Information]";
    private static final String SECTION_PJUMPS = "[Pjumps]";
    private static final String SECTION_THETA = "[Theta]";
    private static final String SECTION_SINGULARITIES = "[Singularities]";
    private static final String KEY_VALUES = "values";
    private static final String KEY_INDICES = "indices";
    private static final String KEY_SINGULARITIES = "singularities";
    private static final String KEY_VERTICES = "Vertices";
    private static final String KEY_EDGES = "Edges";
    private static final String KEY_FACES = "Faces";
    private static final String VALUE_DELIMITER = ";";

    private CrossFieldLoader() {
    }

    /**
     * Load an NDF file and bind it to {@code mesh}.
     *
     * @param ndfPath filesystem path to the .ndf file
     * @param mesh    half-edge mesh whose iteration order matches the NDF arrays
     * @throws IOException              if reading the file fails
     * @throws IllegalArgumentException if section sizes disagree with the mesh
     * @throws RuntimeException wraps any {@link IOException} thrown while re-parsing the buffered content
     * @return populated CrossField (only solver-output fields)
     */
    public static CrossField load(String ndfPath, HalfEdgeMesh mesh) throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader(ndfPath))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            String content = sb.toString();

            Map<String, Map<String, String>> sections = new HashMap<>();
            Map<String, String> current = null;
            try (BufferedReader stringReader = new BufferedReader(new StringReader(content))) {
                while ((line = stringReader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty()) {
                        continue;
                    }
                    if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                        current = new HashMap<>();
                        sections.put(trimmed, current);
                        continue;
                    }
                    if (current == null) {
                        continue;
                    }
                    int eq = trimmed.indexOf('=');
                    if (eq < 0) {
                        continue;
                    }
                    String key = trimmed.substring(0, eq).trim();
                    String value = trimmed.substring(eq + 1).trim();
                    if (value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
                        value = value.substring(1, value.length() - 1);
                    }
                    current.put(key, value);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            Map<String, String> info = sections.getOrDefault(SECTION_INFORMATION, Map.of());
            verifyCount(info, KEY_VERTICES, mesh.vertexCount());
            verifyCount(info, KEY_EDGES, mesh.edgeCount());
            verifyCount(info, KEY_FACES, mesh.faceCount());

            int[] pjumps = parseIntList(sections, SECTION_PJUMPS, KEY_VALUES);
            float[] theta = parseFloatList(sections, SECTION_THETA, KEY_VALUES);
            if (pjumps.length != mesh.edgeCount()) {
                throw new IllegalArgumentException(
                        "[Pjumps] length " + pjumps.length + " != mesh edges " + mesh.edgeCount());
            }
            if (theta.length != mesh.faceCount()) {
                throw new IllegalArgumentException(
                        "[Theta] length " + theta.length + " != mesh faces " + mesh.faceCount());
            }

            int[] singIndices = parseIntList(sections, SECTION_SINGULARITIES, KEY_INDICES);
            int[] singValues = parseIntList(sections, SECTION_SINGULARITIES, KEY_SINGULARITIES);
            if (singIndices.length != singValues.length) {
                throw new IllegalArgumentException("[Singularities] indices/values length mismatch: "
                        + singIndices.length + " vs " + singValues.length);
            }

            CrossField cf = new CrossField(mesh);
            cf.theta = theta;
            cf.periodJump = pjumps;
            cf.singularityIndexQuarter = new int[mesh.vertexCount()];
            cf.singularities.clear();
            for (int i = 0; i < singIndices.length; i++) {
                int vAi = singIndices[i];
                int iQuarter = singValues[i];
                cf.singularityIndexQuarter[vAi] = iQuarter;
                cf.singularities.add(new Singularity(mesh.vertexIdAt(vAi), iQuarter));
            }
            return cf;
        }
    }

    private static void verifyCount(Map<String, String> info, String key, int actual) {
        String declared = info.get(key);
        if (declared == null) {
            return;
        }
        int n = Integer.parseInt(declared.trim());
        if (n != actual) {
            throw new IllegalArgumentException(
                    "[Information] " + key + "=" + n + " != mesh " + key.toLowerCase() + " " + actual);
        }
    }

    private static int[] parseIntList(Map<String, Map<String, String>> sections, String section, String key) {
        String raw = require(sections, section, key);
        String[] parts = raw.split(VALUE_DELIMITER);
        int n = parts.length;
        if (n > 0 && parts[n - 1].isEmpty()) {
            n--; // trailing semicolon
        }
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            out[i] = Integer.parseInt(parts[i].trim());
        }
        return out;
    }

    private static float[] parseFloatList(Map<String, Map<String, String>> sections, String section, String key) {
        String raw = require(sections, section, key);
        String[] parts = raw.split(VALUE_DELIMITER);
        int n = parts.length;
        if (n > 0 && parts[n - 1].isEmpty()) {
            n--;
        }
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            out[i] = Float.parseFloat(parts[i].trim());
        }
        return out;
    }

    private static String require(Map<String, Map<String, String>> sections, String section, String key) {
        Map<String, String> kv = sections.get(section);
        if (kv == null) {
            throw new IllegalArgumentException("NDF missing section " + section);
        }
        String v = kv.get(key);
        if (v == null) {
            throw new IllegalArgumentException("NDF section " + section + " missing key " + key);
        }
        return v;
    }
}
