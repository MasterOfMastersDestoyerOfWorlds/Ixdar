package ixdar.geometry.mesh.quadlayout.crossfield.constraint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.quadlayout.crossfield.CrossField;

public class FeatureEdgeConstraints {
    /**
     * Hard-pins the cross in both faces incident to every interior edge in
     * {@link CrossField#alignmentEdgeIds} to that edge's direction. Edge ids are
     * visited in sorted order so the outcome is deterministic when a face touches
     * several feature edges.
     *
     * <p>See also: BZK09 Section 5.2
     *
     * @param mesh       half-edge mesh providing edge geometry
     * @param crossField cross field receiving the per-face constraint annotations
     * @return number of newly constrained faces
     */
    public static int applyFeatureEdgeConstraints(HalfEdgeMesh mesh, CrossField crossField) {
        int addedConstraints = 0;
        List<Integer> sortedAlignmentEdgeIds = new ArrayList<>(crossField.alignmentEdgeIds);
        Collections.sort(sortedAlignmentEdgeIds);
        for (int edgeId : sortedAlignmentEdgeIds) {
            if (mesh.isBoundaryEdge(edgeId)) {
                continue;
            }
            int halfEdge = mesh.edgeHalfEdge(edgeId);
            int twin = mesh.halfEdgeTwin(halfEdge);
            int faceAActiveId = crossField.faceIdToActive.get(mesh.halfEdgeFace(halfEdge));
            int faceBActiveId = crossField.faceIdToActive.get(mesh.halfEdgeFace(twin));
            if (crossField.faceConstrained[faceAActiveId] && crossField.faceConstrained[faceBActiveId]) {
                continue;
            }
            int v0 = mesh.halfEdgeVertex(halfEdge);
            int v1 = mesh.halfEdgeEndVertex(halfEdge);
            Vector3f vertex0Position = mesh.vertexPosition(v0);
            Vector3f vertex1Position = mesh.vertexPosition(v1);
            Vector3f edgeDir = new Vector3f(vertex1Position).sub(vertex0Position);
            for (int sideAi : new int[] { faceAActiveId, faceBActiveId }) {
                if (crossField.faceConstrained[sideAi]) {
                    continue;
                }
                float angle = mesh.projectDirectionToFaceAngle(edgeDir, sideAi, crossField.faceY[sideAi],
                        crossField.faceX[sideAi]);
                crossField.faceConstrained[sideAi] = true;
                crossField.faceConstraintAngle[sideAi] = CrossField.canonicalizeMod(angle);
                crossField.faceConstraintSource[sideAi] = ConstraintSource.FEATURE;
                addedConstraints++;
            }
        }
        return addedConstraints;
    }
}
