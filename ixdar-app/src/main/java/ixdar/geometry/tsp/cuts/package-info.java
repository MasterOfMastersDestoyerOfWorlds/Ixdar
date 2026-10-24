/**
 * TSP knot-resolution bookkeeping: `CutMatchList` accumulates cut/match pairs with delta cost,
 * `BalanceMap` polices segment balance; the knot groups that prevent multi-cycle joins are a
 * union-find forest on `engines.KnotEngine`. Mutually
 * recursive with `knot` and `shell`; reaches into rendering for debug drawing.
 */
package ixdar.geometry.tsp.cuts;
