package ixdar.geometry.mesh.quadlayout.extraction;

/**
 * QEx (Ebke 2013) quad-mesh edge — pair of {@link QPort}s linked by an
 * iso-line of the parametric domain. {@code portA} sits at one quad-vertex
 * pointing toward the other; {@code portB} sits at the other pointing back.
 */
public record QEdge(int id, int portA, int portB) {}
