/**
 * One class per terminal verb, each extending `TerminalCommand` with `@CommandAnnotation`, whose
 * `acts` names the subsystems it acts on. The terminal refuses a command its scene shows no data
 * for, and a command reads that data through `Terminal.dataset`, never the scene's type.
 */
package ixdar.gui.terminal.commands;
