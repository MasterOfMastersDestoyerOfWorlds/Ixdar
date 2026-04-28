package ixdar.geometry.mesh.quadlayout.boundary;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.quadlayout.boundary.BoundaryCapper.CapResult;

/**
 * Removes the cap regions added by {@link BoundaryCapper} from a quad mesh
 * produced downstream by the QGP pipeline.
 *
 * <p>Quads whose four corner positions all sit within a tolerance of one of
 * the cap centroids are considered cap quads and dropped. The boundary loops
 * re-emerge automatically as edges with a single incident face.
 */
public final class BoundaryUncapper {

    private static final int FLOATS_PER_VERTEX = 3;

    private BoundaryUncapper() {
    }

    public static ArrayMesh uncap(ArrayMesh quadMesh, CapResult capResult) {
        if (capResult.capVertexIds().length == 0) {
            return quadMesh;
        }
        if (quadMesh.getVertsPerFace() != 4) {
            throw new IllegalArgumentException("BoundaryUncapper requires a quad mesh (vertsPerFace=4)");
        }

        int loopCount = capResult.originalLoops().size();
        Vector3f[] centroids = new Vector3f[loopCount];
        float[] capRadii = new float[loopCount];
        ArrayMesh closed = capResult.closedMesh();
        Vector3f tmp = new Vector3f();
        for (int li = 0; li < loopCount; li++) {
            int centroidVertex = capResult.capVertexIds()[li];
            centroids[li] = closed.vertexPosition(centroidVertex, new Vector3f());
            int[] loop = capResult.originalLoops().get(li);
            float maxR = 0f;
            for (int v : loop) {
                closed.vertexPosition(v, tmp);
                float d = tmp.distance(centroids[li]);
                if (d > maxR) {
                    maxR = d;
                }
            }
            // Allow some slack so that quads slightly outside the loop disk
            // are still classified as cap quads. Documented tolerance per
            // ticket: 0.5 * meshExtent / sqrt(faceCount), but the loop's own
            // radius is a much tighter and locally appropriate bound.
            capRadii[li] = maxR * 1.25f;
        }

        Vector3f extentMin = new Vector3f();
        Vector3f extentMax = new Vector3f();
        quadMesh.boundsMin(extentMin);
        quadMesh.boundsMax(extentMax);
        float meshExtent = extentMax.sub(extentMin).length();
        int faceCount = Math.max(1, quadMesh.faceCount());
        float globalTol = 0.5f * meshExtent / (float) Math.sqrt(faceCount);

        float[] positions = quadMesh.copyPositions();
        int[] faceIndices = quadMesh.copyFaceIndices();
        int faces = faceIndices.length / 4;

        boolean[] isCapQuad = new boolean[faces];
        int capQuadCount = 0;
        Vector3f corner = new Vector3f();
        for (int f = 0; f < faces; f++) {
            int matchedLoop = -1;
            for (int c = 0; c < 4; c++) {
                int v = faceIndices[f * 4 + c];
                int o = v * FLOATS_PER_VERTEX;
                corner.set(positions[o], positions[o + 1], positions[o + 2]);
                int hit = nearestCapLoop(corner, centroids, capRadii, globalTol);
                if (hit < 0) {
                    matchedLoop = -1;
                    break;
                }
                if (matchedLoop == -1) {
                    matchedLoop = hit;
                } else if (matchedLoop != hit) {
                    matchedLoop = -1;
                    break;
                }
            }
            if (matchedLoop >= 0) {
                isCapQuad[f] = true;
                capQuadCount++;
            }
        }

        if (capQuadCount == 0) {
            return quadMesh;
        }

        int keptFaces = faces - capQuadCount;
        int[] keptIndices = new int[keptFaces * 4];
        int cursor = 0;
        for (int f = 0; f < faces; f++) {
            if (isCapQuad[f]) {
                continue;
            }
            int base = f * 4;
            keptIndices[cursor++] = faceIndices[base];
            keptIndices[cursor++] = faceIndices[base + 1];
            keptIndices[cursor++] = faceIndices[base + 2];
            keptIndices[cursor++] = faceIndices[base + 3];
        }

        return new ArrayMesh(positions, null, keptIndices, 4);
    }

    private static int nearestCapLoop(Vector3f point, Vector3f[] centroids, float[] capRadii, float globalTol) {
        for (int i = 0; i < centroids.length; i++) {
            float d = point.distance(centroids[i]);
            if (d <= Math.max(capRadii[i], globalTol)) {
                return i;
            }
        }
        return -1;
    }
}
