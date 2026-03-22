package ixdar.gui.terminal;

import java.util.Map;
import java.util.function.Supplier;

import ixdar.annotations.command.CommandRegistry_Commands;
import ixdar.annotations.command.TerminalOption;

public class CommandMap {

    public static final Map<String, Supplier<? extends TerminalOption>> MAP;

    static {
        MAP = CommandRegistry_Commands.MAP;
    }
}
