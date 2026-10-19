/**
 * Mesh modifiers: subdivide, extrude, inset, solidify, mirror, loop cut, bridge loops, instance-on-
 * points, mark_edges. Nodes read their input in place by active ids, keep per-element data in
 * id-indexed arrays, and build a new output mesh with the half-edge bulk builders.
 */
package ixdar.nodegraph.nodes.modifier;
