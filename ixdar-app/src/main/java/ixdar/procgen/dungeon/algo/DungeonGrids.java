package ixdar.procgen.dungeon.algo;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.nodes.api.BoolField;
import ixdar.geometry.mesh.nodes.api.IntField;
import ixdar.geometry.mesh.nodes.api.Vector3Field;
import ixdar.procgen.dungeon.values.CellType;

/**
 * Builders and readers for the dungeon pipeline's general geometry shapes: rooms as a point
 * cloud with a per-vertex {@link #HALF_EXTENT} attribute, room connectivity as a mesh whose
 * edges are the graph, tile grids as grid faces (2D) or a point lattice (3D) with a per-cell
 * {@link #CELL_TYPE} attribute.
 */
public final class DungeonGrids {

    /** Slot name for the per-vertex {@link Vector3Field} of room half extents. */
    public static final String HALF_EXTENT = "half_extent";

    /** Slot name for the per-cell {@link IntField} of {@link CellType} ordinals. */
    public static final String CELL_TYPE = "cell_type";

    public static final float NUM_0_5 = 0.5f;
    public static final float NUM_1e_6 = 1e-6f;

    private DungeonGrids() {
    }

    /**
     * Rooms as points: one vertex per packed xyz center, half extents as a vertex attribute.
     *
     * @param centers     packed xyz room centers (length {@code 3 * roomCount})
     * @param halfExtents packed xyz half extents, same length as {@code centers}
     * @throws IllegalArgumentException if the two arrays differ in length
     * @return bundle of a point-cloud mesh with the {@link #HALF_EXTENT} slot
     */
    public static GeometryBundle pointBundle(float[] centers, float[] halfExtents) {
        if (centers.length != halfExtents.length) {
            throw new IllegalArgumentException(
                    "centers length " + centers.length + " != halfExtents length " + halfExtents.length);
        }
        HalfEdgeMesh mesh = new HalfEdgeMesh();
        for (int i = 0; i < centers.length; i += 3) {
            mesh.addVertex(centers[i], centers[i + 1], centers[i + 2]);
        }
        return GeometryBundle.ofMesh(mesh).withSlot(HALF_EXTENT, new Vector3Field(halfExtents.clone()));
    }

    /**
     * A mesh over {@code source}'s vertices whose wire edges are the given graph.
     *
     * @param source    mesh providing vertex positions (dense order is preserved)
     * @param edgePairs flat pairs of dense vertex indices, one pair per edge
     * @return fresh mesh with the same vertices and one edge per pair, in pair order
     */
    public static HalfEdgeMesh edgeMesh(MeshTopology source, int[] edgePairs) {
        HalfEdgeMesh mesh = new HalfEdgeMesh();
        Vector3f p = new Vector3f();
        int n = source.vertexCount();
        for (int i = 0; i < n; i++) {
            source.vertexPosition(source.vertexIdAt(i), p);
            mesh.addVertex(p.x, p.y, p.z);
        }
        for (int i = 0; i < edgePairs.length; i += 2) {
            mesh.addEdge(edgePairs[i], edgePairs[i + 1]);
        }
        return mesh;
    }

    /**
     * The {@link #HALF_EXTENT} attribute of a rooms bundle.
     *
     * @param bundle rooms (or rooms-derived) bundle
     * @throws IllegalArgumentException if the slot is missing or its length mismatches the mesh
     * @return per-vertex half extents, one per dense vertex index
     */
    public static Vector3Field halfExtents(GeometryBundle bundle) {
        Object slot = bundle.slots().get(HALF_EXTENT);
        if (!(slot instanceof Vector3Field field)) {
            throw new IllegalArgumentException("geometry carries no '" + HALF_EXTENT + "' Vector3Field slot");
        }
        if (field.length() != bundle.mesh().vertexCount()) {
            throw new IllegalArgumentException("'" + HALF_EXTENT + "' length " + field.length()
                    + " != vertex count " + bundle.mesh().vertexCount());
        }
        return field;
    }

    /**
     * Edges of {@code mesh} that a selection keeps, as dense vertex index pairs.
     *
     * @param mesh      graph mesh whose edges are candidates
     * @param selection per-edge selection in dense edge order, or null to keep every edge
     * @return flat pairs of dense vertex indices in dense edge order
     */
    public static int[] selectedEdgePairs(MeshTopology mesh, BoolField selection) {
        int n = mesh.vertexCount();
        int maxId = 0;
        for (int i = 0; i < n; i++) {
            maxId = Math.max(maxId, mesh.vertexIdAt(i));
        }
        int[] denseOf = new int[maxId + 1];
        for (int i = 0; i < n; i++) {
            denseOf[mesh.vertexIdAt(i)] = i;
        }
        int e = mesh.edgeCount();
        int kept = 0;
        for (int ei = 0; ei < e; ei++) {
            if (selection == null || selection.get(ei)) {
                kept++;
            }
        }
        int[] pairs = new int[kept * 2];
        int w = 0;
        for (int ei = 0; ei < e; ei++) {
            if (selection != null && !selection.get(ei)) {
                continue;
            }
            int he = mesh.edgeHalfEdge(mesh.edgeIdAt(ei));
            pairs[w++] = denseOf[mesh.halfEdgeVertex(he)];
            // The twin's start vertex is this half-edge's end; wire edges have no next half-edge.
            pairs[w++] = denseOf[mesh.halfEdgeVertex(mesh.halfEdgeTwin(he))];
        }
        return pairs;
    }

    /**
     * A 2D tile grid as grid geometry: quad faces on the XZ plane over {@code [0,w]x[0,h]}
     * (grid y along world Z), one face per cell in row-major order, cell types per face.
     *
     * @param width  grid width in cells
     * @param height grid height in cells
     * @param cells  row-major cell types (length {@code width * height})
     * @throws IllegalArgumentException if {@code cells} does not match the dimensions
     * @return bundle of the grid mesh with the {@link #CELL_TYPE} per-face slot
     */
    public static GeometryBundle gridBundle(int width, int height, CellType[] cells) {
        if (cells.length != width * height) {
            throw new IllegalArgumentException(
                    "cells length " + cells.length + " != " + width + "*" + height);
        }
        HalfEdgeMesh mesh = new HalfEdgeMesh();
        int[][] vid = new int[width + 1][height + 1];
        for (int x = 0; x <= width; x++) {
            for (int y = 0; y <= height; y++) {
                vid[x][y] = mesh.addVertex(x, 0f, y);
            }
        }
        int[] ordinals = new int[cells.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                mesh.addFace(vid[x][y], vid[x][y + 1], vid[x + 1][y + 1], vid[x + 1][y]);
                ordinals[y * width + x] = cells[y * width + x].ordinal();
            }
        }
        return GeometryBundle.ofMesh(mesh).withSlot(CELL_TYPE, new IntField(ordinals));
    }

    /**
     * A 3D tile grid as a point lattice: one vertex per cell at the cell center
     * {@code (x+0.5, y+0.5, z+0.5)}, cell types per vertex.
     *
     * @param width  grid width in cells (X)
     * @param height grid height in floors (Y)
     * @param depth  grid depth in cells (Z)
     * @param cells  cell types indexed {@code x + width * (z + depth * y)}
     * @throws IllegalArgumentException if {@code cells} does not match the dimensions
     * @return bundle of the lattice mesh with the {@link #CELL_TYPE} per-vertex slot
     */
    public static GeometryBundle latticeBundle(int width, int height, int depth, CellType[] cells) {
        if (cells.length != width * height * depth) {
            throw new IllegalArgumentException(
                    "cells length " + cells.length + " != " + width + "*" + height + "*" + depth);
        }
        HalfEdgeMesh mesh = new HalfEdgeMesh();
        int[] ordinals = new int[cells.length];
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < depth; z++) {
                for (int x = 0; x < width; x++) {
                    int i = x + width * (z + depth * y);
                    mesh.addVertex(x + NUM_0_5, y + NUM_0_5, z + NUM_0_5);
                    ordinals[i] = cells[i].ordinal();
                }
            }
        }
        return GeometryBundle.ofMesh(mesh).withSlot(CELL_TYPE, new IntField(ordinals));
    }

    /**
     * Dimensions of a {@link #gridBundle} geometry, read from its vertex extents.
     *
     * @param bundle 2D tile-grid bundle
     * @return {@code {width, height}} in cells
     */
    public static int[] gridDims(GeometryBundle bundle) {
        MeshTopology mesh = bundle.mesh();
        Vector3f p = new Vector3f();
        float maxX = 0f;
        float maxZ = 0f;
        for (int i = 0; i < mesh.vertexCount(); i++) {
            mesh.vertexPosition(mesh.vertexIdAt(i), p);
            maxX = Math.max(maxX, p.x);
            maxZ = Math.max(maxZ, p.z);
        }
        return new int[] { Math.round(maxX), Math.round(maxZ) };
    }

    /**
     * Cell types of a {@link #gridBundle} geometry, located by face centroid.
     *
     * @param bundle 2D tile-grid bundle
     * @param width  grid width in cells
     * @param height grid height in cells
     * @return row-major cell types (length {@code width * height})
     */
    public static CellType[] gridCells(GeometryBundle bundle, int width, int height) {
        MeshTopology mesh = bundle.mesh();
        IntField field = cellTypeField(bundle, mesh.faceCount());
        CellType[] values = CellType.values();
        CellType[] cells = new CellType[width * height];
        Vector3f p = new Vector3f();
        for (int fi = 0; fi < mesh.faceCount(); fi++) {
            int fid = mesh.faceIdAt(fi);
            int fv = mesh.faceVertexCount(fid);
            float cx = 0f;
            float cz = 0f;
            for (int k = 0; k < fv; k++) {
                mesh.vertexPosition(mesh.faceVertexAt(fid, k), p);
                cx += p.x;
                cz += p.z;
            }
            int x = (int) Math.floor(cx / fv);
            int y = (int) Math.floor(cz / fv);
            cells[y * width + x] = values[field.get(fi)];
        }
        return cells;
    }

    /**
     * Dimensions of a {@link #latticeBundle} geometry, read from its vertex extents.
     *
     * @param bundle 3D tile-lattice bundle
     * @return {@code {width, height, depth}} in cells
     */
    public static int[] latticeDims(GeometryBundle bundle) {
        MeshTopology mesh = bundle.mesh();
        Vector3f p = new Vector3f();
        float maxX = 0f;
        float maxY = 0f;
        float maxZ = 0f;
        for (int i = 0; i < mesh.vertexCount(); i++) {
            mesh.vertexPosition(mesh.vertexIdAt(i), p);
            maxX = Math.max(maxX, p.x);
            maxY = Math.max(maxY, p.y);
            maxZ = Math.max(maxZ, p.z);
        }
        return new int[] {
                (int) Math.floor(maxX) + 1,
                (int) Math.floor(maxY) + 1,
                (int) Math.floor(maxZ) + 1 };
    }

    /**
     * Cell types of a {@link #latticeBundle} geometry, located by vertex position.
     *
     * @param bundle 3D tile-lattice bundle
     * @param width  grid width in cells (X)
     * @param height grid height in floors (Y)
     * @param depth  grid depth in cells (Z)
     * @return cell types indexed {@code x + width * (z + depth * y)}
     */
    public static CellType[] latticeCells(GeometryBundle bundle, int width, int height, int depth) {
        MeshTopology mesh = bundle.mesh();
        IntField field = cellTypeField(bundle, mesh.vertexCount());
        CellType[] values = CellType.values();
        CellType[] cells = new CellType[width * height * depth];
        Vector3f p = new Vector3f();
        for (int vi = 0; vi < mesh.vertexCount(); vi++) {
            mesh.vertexPosition(mesh.vertexIdAt(vi), p);
            int x = (int) Math.floor(p.x);
            int y = (int) Math.floor(p.y);
            int z = (int) Math.floor(p.z);
            cells[x + width * (z + depth * y)] = values[field.get(vi)];
        }
        return cells;
    }

    private static IntField cellTypeField(GeometryBundle bundle, int expectedLength) {
        Object slot = bundle.slots().get(CELL_TYPE);
        if (!(slot instanceof IntField field)) {
            throw new IllegalArgumentException("geometry carries no '" + CELL_TYPE + "' IntField slot");
        }
        if (field.length() != expectedLength) {
            throw new IllegalArgumentException("'" + CELL_TYPE + "' length " + field.length()
                    + " != element count " + expectedLength);
        }
        return field;
    }
}
