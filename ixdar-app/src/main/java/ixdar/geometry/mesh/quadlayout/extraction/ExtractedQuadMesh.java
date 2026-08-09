package ixdar.geometry.mesh.quadlayout.extraction;

/**
 * The quad mesh extracted from the relaxed integer grid map: one vertex per
 * integer grid point preimage, connected by traced unit iso-segments. Ports are
 * kept clockwise per vertex so the layout regrouping can navigate the mesh.
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

    /** Patch whose chart {@link #chartU}/{@link #chartV} are stored in. */
    public int[] chartPatchId;

    /** Integer grid u of each vertex in its stored chart. */
    public double[] chartU;

    /** Integer grid v of each vertex in its stored chart. */
    public double[] chartV;

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

    /** The owner vertex's grid u in the port face's chart. */
    public double[] portChartU;

    /** The owner vertex's grid v in the port face's chart. */
    public double[] portChartV;

    /** Opposite port reached by tracing, {@link #NONE} until connected. */
    public int[] portConnection;

    /** Quarter turns of the automorphism from this port's chart to its opposite's. */
    public int[] connectionTurns;

    /** Grid u translation of that automorphism. */
    public int[] connectionTranslationU;

    /** Grid v translation of that automorphism. */
    public int[] connectionTranslationV;

    /** Extracted quads. */
    public int quadCount;

    /** Vertex ids of each quad, four per quad in cycle order. */
    public int[] quadCorner;

    /** Unique undirected quad edges. */
    public int quadEdgeCount;

    /** First vertex of each quad edge. */
    public int[] quadEdgeVertexA;

    /** Second vertex of each quad edge. */
    public int[] quadEdgeVertexB;

    /**
     * The mesh's Euler characteristic, which must match the surface it covers.
     *
     * @return vertices minus edges plus faces
     */
    public int eulerCharacteristic() {
        return quadVertexCount - quadEdgeCount + quadCount;
    }
}
