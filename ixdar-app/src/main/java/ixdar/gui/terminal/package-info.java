/**
 * The in-app REPL. `Terminal` owns command/tool registries, dispatches typed lines, renders
 * scrollable history. Commands come from the annotation-generated registry via `CommandMap`.
 * `Terminal.current` is a mutable static that scenes reassign to route keyboard input.
 */
package ixdar.gui.terminal;
