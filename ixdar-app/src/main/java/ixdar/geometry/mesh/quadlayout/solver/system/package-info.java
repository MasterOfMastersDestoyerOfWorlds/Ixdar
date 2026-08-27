/**
 * The DOF-system state and the solve strategies decoupled from the stacks:
 * {@code DofSystem} holds the canonical solution/frozen state plus assembly,
 * energy, and write-back hooks; each outer loop (single solve, Newton, greedy
 * rounding, lazy constraints, power iteration) is its own strategy class.
 */
package ixdar.geometry.mesh.quadlayout.solver.system;
