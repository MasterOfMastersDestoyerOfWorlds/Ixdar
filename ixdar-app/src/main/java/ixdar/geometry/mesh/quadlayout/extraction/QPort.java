package ixdar.geometry.mesh.quadlayout.extraction;

import org.joml.Vector3f;

/**
 * QEx (Ebke 2013) "port" — a directed exit from a {@link QVert} in one of
 * the four cardinal directions of the parametric domain.
 *
 * <p>Each QVert sources <i>at least</i> 4 ports (FACE / EDGE QVerts emit
 * exactly 4; VERT QVerts emit one per incident parametric quadrant, which
 * for a regular vertex is also 4 but for a singularity may be more or
 * fewer).
 *
 * <p>Ports are linked into a cyclic doubly-linked list per QVert via
 * {@link #prevPort} / {@link #nextPort} so that QFace assembly (Stage 4)
 * can walk port → connected-edge-target-port → next-port-of-target's-QVert
 * → ... and recognise 4-cycles as quad faces.
 *
 * <p>Mutability: {@code connectedEdgeId} and the prev/next links are
 * filled in after construction. Wrapped in a class (not a record) for
 * mutability.
 */
public final class QPort {

    public final int id;
    public final int qVertId;
    public final QVert.Source source;
    public final int sourceId;        // mesh vert / edge / face id
    public final int faceId;          // face this port lives in (UV frame anchor)
    public final float uvU;
    public final float uvV;
    public final float dirU;
    public final float dirV;
    public final Vector3f position;

    public int prevPort = -1;
    public int nextPort = -1;
    public int connectedEdgeId = -1;
    public boolean connected = false;

    public QPort(int id, int qVertId, QVert.Source source, int sourceId,
                 int faceId, float uvU, float uvV, float dirU, float dirV,
                 Vector3f position) {
        this.id = id;
        this.qVertId = qVertId;
        this.source = source;
        this.sourceId = sourceId;
        this.faceId = faceId;
        this.uvU = uvU;
        this.uvV = uvV;
        this.dirU = dirU;
        this.dirV = dirV;
        this.position = position;
    }
}
