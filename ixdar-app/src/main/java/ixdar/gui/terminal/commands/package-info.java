/**
 * One class per terminal verb, each extending `TerminalCommand` with `@CommandAnnotation`. Commands
 * reach into scene singletons and `FileManagement`, so they are not testable in isolation.
 */
package ixdar.gui.terminal.commands;
