package ixdar.geometry.mesh.quadlayout.extraction;

import java.util.Arrays;

import ixdar.geometry.mesh.data.representation.ArrayMesh;

/**
 * The quad mesh extracted from the relaxed integer grid map: one vertex per
 * integer grid point preimage, connected by traced unit iso-segments. Ports are
 * kept in surface clockwise order per vertex, so one CSR step is always the
 * same rotational ring step for the layout regrouping.
 *
 * <p>
 * See also: EBC13
 */
public final class ExtractedQuadMesh {

    /** Vertex kind: the preimage coincides with a copy mesh vertex. */
    public static final int KIND_MESH_VERTEX = 0;

    /** Vertex kind: the preimage lies strictly inside a copy mesh edge. */
    public static final int KIND_EDGE_INTERIOR = 1;

    /** Vertex kind: the preimage lies strictly inside a copy mesh face. */
    public static final int KIND_FACE_INTERIOR = 2;

    /** Sentinel for an absent port connection or id. */
    public static final int NONE = -1;

    /** Floats per 3D position. */
    public static final int POSITION_FLOATS = 3;

    /** Corners of one quad. */
    public static final int QUAD_CORNERS = 4;

    /** Quad mesh vertices. */
    public int quadVertexCount;

    /** Vertex positions on the surface, packed xyz per vertex. */
    public float[] positions;

    /** One of the {@code KIND_*} constants per vertex. */
    public int[] vertexKind;

    /** The copy vertex, edge, or face id the vertex's preimage sits on. */
    public int[] anchorEntityId;

    /** Ports over all vertices; each is one outgoing iso-line direction. */
    public int portCount;

    /** CSR start of each vertex's clockwise port list, length count plus one. */
    public int[] portStart;

    /** Owning vertex per port. */
    public int[] portOwner;

    /** Copy face whose patch chart holds the port's direction. */
    public int[] portFace;

    /** Direction as quarter turns from {@code +u} in the port face's chart. */
    public int[] portDirectionTurns;

    /** Opposite port reached by tracing, {@link #NONE} until connected. */
    public int[] portConnection;

    /** Extracted quads. */
    public int quadCount;

    /** Vertex ids of each quad, four per quad in cycle order. */
    public int[] quadCorner;

    /** Unique undirected quad edges. */
    public int quadEdgeCount;

    /**
     * The mesh's Euler characteristic, which must match the surface it covers.
     *
     * @return vertices minus edges plus faces
     */
    public int eulerCharacteristic() {
        return quadVertexCount - quadEdgeCount + quadCount;
    }

    /**
     * The extracted quads as a dense mesh, positions and corner indices trimmed
     * to the emitted counts.
     *
     * @return the packed quad mesh
     */
    public ArrayMesh toArrayMesh() {
        return ArrayMesh.fromQuads(
                Arrays.copyOf(positions, quadVertexCount * POSITION_FLOATS),
                Arrays.copyOf(quadCorner, quadCount * QUAD_CORNERS));
    }
}
