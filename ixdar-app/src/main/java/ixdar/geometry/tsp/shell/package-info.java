/**
 * `Shell`: a point list that starts as a convex hull and merges toward a TSP path, with the cached
 * `DistanceMatrix` (Apache commons-math eigendecomposition). Constructing a `Shell` eagerly builds
 * a `KnotEngine`. `StringBuff` is the depth-tagged trace a `Shell` keeps.
 */
package ixdar.geometry.tsp.shell;
