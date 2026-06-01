package ixdar.geometry.mesh.quadlayout.crossfield.constraint;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh.EdgeFaceIds;
import ixdar.geometry.mesh.quadlayout.crossfield.CrossField;

public class FeatureEdgeConstraints {
      /**
     * BZK09 §5.2 feature-edge alignment constraints: edges whose dihedral angle
     * between the two incident face normals exceeds {@link #featureDihedralCos}
     * (default cos 30° = 0.866) are treated as sharp creases. The cross is aligned
     * with the edge direction in both incident faces. Binary include/exclude
     * decision by dihedral threshold — no scale-invariant adaptation, no confidence
     * weighting; CIE16 is a richer alternative we do not implement.
     *
     * <p>
     * Without this pass, sharp models like fandisk produce many spurious
     * singularities because the field has no incentive to follow features.
     *
     * @param mesh       half-edge mesh whose dihedral angles are measured
     * @param crossField cross field receiving the per-face constraint annotations
     * @return number of newly constrained faces
     */
      public static int applyFeatureEdgeConstraints(HalfEdgeMesh mesh, CrossField crossField) {
        int addedConstraints = 0;
        for (int activeEdgeIndex = 0; activeEdgeIndex < mesh.edgeCount(); activeEdgeIndex++) {
            EdgeFaceIds edgeFaceIds = mesh.edgeFaceIds(activeEdgeIndex);
            if (mesh.isBoundaryEdge(edgeFaceIds.edgeId)) {
                continue;
            }
            Vector3f faceANormal = mesh.faceNormal(edgeFaceIds.faceA);
            Vector3f faceBNormal = mesh.faceNormal(edgeFaceIds.faceB);
            float dot = faceANormal.dot(faceBNormal);
            if (dot >= crossField.featureDihedralCos) {
                continue;
            }

            int faceAActiveId = crossField.faceIdToActive.get(edgeFaceIds.faceA);
            int faceBActiveId = crossField.faceIdToActive.get(edgeFaceIds.faceB);
            crossField.alignmentEdgeIds.add(edgeFaceIds.edgeId);
            if (crossField.faceConstrained[faceAActiveId] && crossField.faceConstrained[faceBActiveId]) {
                continue;
            }
            int v0 = mesh.halfEdgeVertex(edgeFaceIds.halfEdge);
            int v1 = mesh.halfEdgeEndVertex(edgeFaceIds.halfEdge);
            Vector3f vertex0Position = mesh.vertexPosition(v0);
            Vector3f vertex1Position = mesh.vertexPosition(v1);
            Vector3f edgeDir = new Vector3f(vertex1Position).sub(vertex0Position);
            for (int sideAi : new int[] { faceAActiveId, faceBActiveId }) {
                if (crossField.faceConstrained[sideAi]) {
                    continue;
                }
                float angle = mesh.projectDirectionToFaceAngle(edgeDir, sideAi, crossField.faceY[sideAi], crossField.faceX[sideAi]);
                crossField.faceConstrained[sideAi] = true;
                crossField.faceConstraintAngle[sideAi] = CrossField.canonicalizeMod(angle);
                crossField.faceConstraintSource[sideAi] = ConstraintSource.FEATURE;
                addedConstraints++;
            }
        }
        return addedConstraints;
    }
}
