/**
 * Curve nodes: primitives (circle, bezier, function), conversion to and from meshes, sweep, loft,
 * resample, deform. Curves travel between nodes as `CurveGeometry` under `CurveGeometry.SLOT`; a
 * node reads a curve through its per-curve accessors and closed flag, never from raw offsets, and
 * sets the flag on every curve it makes.
 */
package ixdar.nodegraph.nodes.curve;
