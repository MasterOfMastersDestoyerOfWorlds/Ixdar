/**
 * The in-app REPL. `Terminal` owns command/tool registries, dispatches typed lines, renders
 * scrollable history. Commands come from the annotation-generated registry via `CommandMap`.
 * `Terminal.current` is a mutable static that scenes reassign to route keyboard input.
 * `TerminalParseException` reports a malformed command or `.ix` line.
 */
package ixdar.gui.terminal;
