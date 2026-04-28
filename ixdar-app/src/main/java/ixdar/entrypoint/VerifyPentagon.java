package ixdar.entrypoint;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.CharrotGregoryPatchSampler;
import ixdar.geometry.mesh.nodes.patch.CharrotGregoryPatch;

/**
 * Sanity test for {@link CharrotGregoryPatch} on a clean 5-sided patch.
 * If this produces a recognizable pentagonal disk, the evaluator is
 * fine and the skull chaos must be in the MSC chain construction.
 *
 * <p>Boundary: 5 straight cubic Beziers forming a regular pentagon in
 * the z=0 plane. Expected output: a flat pentagonal disk (or close to
 * it — Charrot-Gregory's interior interpolation may give slight bulge).
 */
public final class VerifyPentagon {

    public static void main(String[] args) {
        int n = 5;
        Vector3f[] corners = new Vector3f[n];
        for (int i = 0; i < n; i++) {
            double theta = (i + 0.5) * (2.0 * Math.PI / n) + Math.PI;
            corners[i] = new Vector3f((float) Math.cos(theta), (float) Math.sin(theta), 0f);
        }
        // Build 5 cubic Beziers along straight lines between adjacent corners.
        Vector3f[][] beziers = new Vector3f[n][];
        for (int i = 0; i < n; i++) {
            Vector3f a = corners[i];
            Vector3f b = corners[(i + 1) % n];
            beziers[i] = new Vector3f[]{
                    new Vector3f(a),
                    new Vector3f(a).lerp(b, 1f/3f),
                    new Vector3f(a).lerp(b, 2f/3f),
                    new Vector3f(b),
            };
        }

        // Sample the patch at the corners + center + midpoints.
        Vector3f tmp = new Vector3f();
        System.out.println("Pentagon CG evaluation:");
        CharrotGregoryPatch.evaluate(beziers, 0f, 0f, tmp);
        System.out.printf("  center           (0, 0)   -> (%.4f, %.4f, %.4f)%n", tmp.x, tmp.y, tmp.z);
        for (int i = 0; i < n; i++) {
            Vector3f c = corners[i];
            CharrotGregoryPatch.evaluate(beziers, c.x, c.y, tmp);
            System.out.printf("  corner %d  (%.3f, %.3f) -> (%.4f, %.4f, %.4f)  [expect (%.3f, %.3f, 0)]%n",
                    i, c.x, c.y, tmp.x, tmp.y, tmp.z, c.x, c.y);
            // Midpoint of edge i
            Vector3f m = new Vector3f(c).lerp(corners[(i + 1) % n], 0.5f);
            CharrotGregoryPatch.evaluate(beziers, m.x, m.y, tmp);
            System.out.printf("  edge %d midpt (%.3f, %.3f) -> (%.4f, %.4f, %.4f)  [expect (%.3f, %.3f, 0)]%n",
                    i, m.x, m.y, tmp.x, tmp.y, tmp.z, m.x, m.y);
        }

        // Also try the sampler on it.
        CharrotGregoryPatchSampler.SampledPatch sp = CharrotGregoryPatchSampler.sample(
                new java.util.ArrayList<>(), beziers, /*ringsPerSpoke=*/8, /*edgeSamples=*/16,
                new int[0], new float[0], 0);
        System.out.println();
        System.out.println("Sampler output: " + (sp.sampledPositions().length / 3) + " verts, "
                + (sp.sampledFaces().length / 3) + " tris");
        // Print min/max XYZ to confirm we stay in the unit pentagon.
        float minX = Float.POSITIVE_INFINITY, maxX = Float.NEGATIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        float minZ = Float.POSITIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        float[] pos = sp.sampledPositions();
        for (int i = 0; i < pos.length; i += 3) {
            minX = Math.min(minX, pos[i]);     maxX = Math.max(maxX, pos[i]);
            minY = Math.min(minY, pos[i + 1]); maxY = Math.max(maxY, pos[i + 1]);
            minZ = Math.min(minZ, pos[i + 2]); maxZ = Math.max(maxZ, pos[i + 2]);
        }
        System.out.printf("  bbox X=[%.4f, %.4f]  Y=[%.4f, %.4f]  Z=[%.4f, %.4f]%n",
                minX, maxX, minY, maxY, minZ, maxZ);
        System.out.printf("  expected: X=[-1, 1]  Y=[-1, 1]  Z=[0, 0]%n");
    }
}
