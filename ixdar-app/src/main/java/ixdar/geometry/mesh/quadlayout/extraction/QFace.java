package ixdar.geometry.mesh.quadlayout.extraction;

/**
 * QEx (Ebke 2013) quad-mesh face — exactly 4 corners (QVert ids) listed in
 * cyclic order. The companion {@code edgeIds} array gives the 4 QEdge ids
 * traversed (also in cyclic order, edge i connects corner i to corner
 * {@code (i+1) % 4}).
 */
public record QFace(int id, int[] cornerQVerts, int[] edgeIds) {}
