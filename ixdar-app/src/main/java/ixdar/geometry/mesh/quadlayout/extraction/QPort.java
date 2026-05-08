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

    /**
     * Construct a port, leaving {@code prevPort}, {@code nextPort}, and
     * {@code connectedEdgeId} unset (caller fills them after the cyclic ring
     * is sorted and the edge tracer pairs ports up).
     *
     * @param id sequential port id (matches the index in the ports list)
     * @param qVertId id of the {@link QVert} this port emits from
     * @param source source kind of the owning QVert (FACE / EDGE / VERT)
     * @param sourceId mesh entity id for {@code source}
     * @param faceId mesh face whose UV frame the port's direction is expressed in
     * @param uvU u coordinate of the port location in {@code faceId}'s frame
     * @param uvV v coordinate of the port location in {@code faceId}'s frame
     * @param dirU u component of the cardinal direction (one of -1, 0, +1)
     * @param dirV v component of the cardinal direction (one of -1, 0, +1)
     * @param position 3D position of the port (= the QVert's position)
     */
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
