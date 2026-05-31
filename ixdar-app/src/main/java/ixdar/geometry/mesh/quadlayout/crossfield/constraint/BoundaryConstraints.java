package ixdar.geometry.mesh.quadlayout.crossfield.constraint;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh.EdgeFaceIds;
import ixdar.geometry.mesh.quadlayout.crossfield.CrossField;

public class BoundaryConstraints {
    /**
     * Add directional constraints on both faces incident to each boundary edge. The
     * cross is aligned with the edge direction so the quadrangulation follows the
     * surface boundary.
     *
     * @param mesh       half-edge mesh whose boundary edges are scanned
     * @param crossField cross field receiving the alignment-edge annotations
     * @return number of newly constrained faces
     */
    public static int applyBoundaryConstraints(HalfEdgeMesh mesh, CrossField crossField) {
        int addedConstraints = 0;
        for (int activeEdgeIndex = 0; activeEdgeIndex < mesh.edgeCount(); activeEdgeIndex++) {
            EdgeFaceIds edgeFaceIds = mesh.edgeFaceIds(activeEdgeIndex);
            if (!mesh.isBoundaryEdge(edgeFaceIds.edgeId))
                continue;
            Vector3f edgeDir = new Vector3f(mesh.vertexPosition(edgeFaceIds.edgeEndVertex))
                    .sub(mesh.vertexPosition(edgeFaceIds.edgeStartVertex));
            crossField.alignmentEdgeIds.add(edgeFaceIds.edgeId);
            for (int faceIndex : new int[] { edgeFaceIds.faceA, edgeFaceIds.faceB }) {
                if (faceIndex == MeshTopology.NONE)
                    continue;
                int faceActiveIndex = crossField.faceIdToActive.get(faceIndex);
                float angle = mesh.projectDirectionToFaceAngle(edgeDir, faceActiveIndex,
                        crossField.faceY[faceActiveIndex],
                        crossField.faceX[faceActiveIndex]);
                if (!crossField.faceConstrained[faceActiveIndex]) {
                    crossField.faceConstrained[faceActiveIndex] = true;
                    addedConstraints++;
                }
                crossField.faceConstraintAngle[faceActiveIndex] = CrossField.canonicalizeMod(angle);
            }
        }
        return addedConstraints;
    }

}
