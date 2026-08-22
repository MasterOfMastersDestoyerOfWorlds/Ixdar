/**
 * TSP knot-resolution bookkeeping: `CutMatchList` accumulates cut/match pairs with delta cost,
 * `BalanceMap` polices segment balance, `DisjointUnionSets` prevents multi-cycle joins. Mutually
 * recursive with `knot` and `shell`; reaches into rendering for debug drawing.
 */
package ixdar.geometry.cuts;
