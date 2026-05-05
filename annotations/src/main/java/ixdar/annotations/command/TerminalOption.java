package ixdar.annotations.command;

public interface TerminalOption {

    /**
     * Single-line usage string shown in help output (e.g. {@code "--name <value>"}).
     *
     * @return human-readable usage syntax for this option
     */
    public abstract String usage();

    /**
     * Description of what this option does, shown alongside {@link #usage()} in help output.
     *
     * @return human-readable description
     */
    public abstract String desc();

    /**
     * Long-form flag name (e.g. {@code "verbose"} for {@code --verbose}).
     *
     * @return the long option name without leading dashes
     */
    public abstract String fullName();

    /**
     * Short-form flag name (e.g. {@code "v"} for {@code -v}).
     *
     * @return the short option name without leading dash
     */
    public abstract String shortName();

    /**
     * Sub-options accepted under this option, if any.
     *
     * @return list of nested options; empty when this option takes no sub-options
     */
    public abstract OptionList options();

    /**
     * Maximum number of positional arguments this option consumes after its flag.
     *
     * @return upper bound on argument count
     */
    public abstract int argLength();

    /**
     * Minimum number of positional arguments this option requires after its flag.
     *
     * @return lower bound on argument count, must satisfy {@code 0 <= minArgLength() <= argLength()}
     */
    public abstract int minArgLength();
}
