/**
 * `Shell`: a point list that starts as a convex hull and merges toward a TSP path, with the cached
 * `DistanceMatrix` (Apache commons-math eigendecomposition). Constructing a `Shell` eagerly builds
 * a `KnotEngine`.
 */
package ixdar.geometry.shell;
