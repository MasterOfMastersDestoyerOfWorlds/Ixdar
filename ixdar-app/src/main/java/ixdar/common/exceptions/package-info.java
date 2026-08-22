/**
 * Exception types, dominated by the balancer family: `SegmentBalanceException` carries cut/match
 * diagnostic state renderable as a `HyperString`; subclasses inherit it via copy constructors. Not
 * domain-neutral; imports the TSP geometry and render packages. `InvalidMeshTopologyException` is
 * the lone unchecked type.
 */
package ixdar.common.exceptions;
