package ixdar.geometry.mesh.quadlayout.crossfield.constraint;

import java.util.Arrays;

import org.joml.Vector3f;

/**
 * One possible hard face pin derived from a stable curvature estimate at a
 * source vertex.
 */
public final class CurvatureConstraintCandidate {
    public final int sourceVertexId;
    public final int faceActiveId;
    public final float angleInFace;
    public final int[] faceActiveIds;
    public final float[] anglesInFace;
    public final int[] footprintVertexIds;
    public final int[] footprintFaceActiveIds;
    public final Vector3f directionWorld;
    public final Vector3f sourcePosition;
    public final float confidence;

    /**
     * Store one candidate curvature-derived face constraint.
     *
     * @param sourceVertexId         source mesh vertex whose curvature estimate
     *                               produced this candidate
     * @param faceActiveIds          active indices of compatible faces to pin
     * @param anglesInFace           canonical cross-field angles for
     *                               {@code faceActiveIds}
     * @param footprintVertexIds     vertices covered by this candidate's curvature
     *                               disk
     * @param footprintFaceActiveIds active face ids touched by the footprint
     * @param directionWorld         unit world-space curvature direction
     * @param sourcePosition         source vertex position
     * @param confidence             larger values indicate a more reliable
     *                               candidate
     */
    public CurvatureConstraintCandidate(int sourceVertexId, int[] faceActiveIds,
            float[] anglesInFace, int[] footprintVertexIds, int[] footprintFaceActiveIds,
            Vector3f directionWorld, Vector3f sourcePosition, float confidence) {
        this.sourceVertexId = sourceVertexId;
        this.faceActiveIds = Arrays.copyOf(faceActiveIds, faceActiveIds.length);
        this.anglesInFace = Arrays.copyOf(anglesInFace, anglesInFace.length);
        this.footprintVertexIds = Arrays.copyOf(footprintVertexIds, footprintVertexIds.length);
        this.footprintFaceActiveIds = Arrays.copyOf(footprintFaceActiveIds, footprintFaceActiveIds.length);
        this.faceActiveId = faceActiveIds[0];
        this.angleInFace = anglesInFace[0];
        this.directionWorld = new Vector3f(directionWorld);
        this.sourcePosition = new Vector3f(sourcePosition);
        this.confidence = confidence;
    }
}
