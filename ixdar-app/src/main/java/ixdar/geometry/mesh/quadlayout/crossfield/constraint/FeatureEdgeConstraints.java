package ixdar.geometry.mesh.quadlayout.crossfield.constraint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.quadlayout.crossfield.CrossField;

public class FeatureEdgeConstraints {
    /**
     * BZK09 §5.2 feature-edge alignment constraints: for every interior edge in
     * {@link CrossField#alignmentEdgeIds} (detected by the parent cross field's
     * dihedral test), align the cross in both incident faces with the edge
     * direction. This hard face pinning is specific to the Bommes mixed-integer
     * solver; the Knöppel path aligns softly via its guidance field instead.
     * Edge ids are visited in sorted order so the pinning outcome is
     * deterministic when a face touches several feature edges.
     *
     * <p>
     * Without this pass, sharp models like fandisk produce many spurious
     * singularities because the field has no incentive to follow features.
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
