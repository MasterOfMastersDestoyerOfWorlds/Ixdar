package ixdar.gui.terminal.commands;

import ixdar.annotations.command.CommandAnnotation;
import ixdar.graphics.render.color.Color;
import ixdar.gui.terminal.Terminal;
import ixdar.scenes.model.ModelCollection;
import ixdar.scenes.model.ModelScene;

/**
 * Terminal command {@code collection}/{@code cn}: browse the open collection from the prompt.
 * {@code list} prints the members with their keep flags, {@code next}/{@code prev} cycle them the
 * way {@code ]} and {@code [} do, and {@code keep}/{@code reject} set the current member's flag
 * and rewrite the manifest.
 */
@CommandAnnotation(id = "cn")
public class CollectionCommand extends TerminalCommand {

    /** Short alias for the command: {@code "cn"}. */
    public static String cmd = "cn";

    /** Subcommand printing every member with its keep flag. */
    public static final String LIST = "list";

    /** Subcommand stepping the cursor forward one member. */
    public static final String NEXT = "next";

    /** Subcommand stepping the cursor back one member. */
    public static final String PREV = "prev";

    /** Subcommand keeping the current member. */
    public static final String KEEP = "keep";

    /** Subcommand rejecting the current member. */
    public static final String REJECT = "reject";

    /**
     * Full command word: {@code "collection"}.
     *
     * @return fully-qualified command name used at the prompt
     */
    @Override
    public String fullName() {
        return "collection";
    }

    /**
     * Short alias for the command: {@code "cn"}.
     *
     * @return short command name used at the prompt
     */
    @Override
    public String shortName() {
        return cmd;
    }

    /**
     * One-line description shown in help output.
     *
     * @return human-readable summary of the command
     */
    @Override
    public String desc() {
        return "browse the open model collection and set keep flags";
    }

    /**
     * Usage hint displayed when the command is mis-invoked or {@code -h} is passed.
     *
     * @return usage string
     */
    @Override
    public String usage() {
        return "usage: cn|collection list|next|prev|keep|reject";
    }

    /**
     * Exact number of trailing arguments expected: the subcommand.
     *
     * @return {@code 1}
     */
    @Override
    public int argLength() {
        return 1;
    }

    /**
     * Apply the subcommand at {@code args[startIdx]} to the terminal's active model scene.
     *
     * @param args full tokenised command line
     * @param startIdx index of the subcommand in {@code args}
     * @param terminal dispatching terminal (holds the active {@link ModelScene})
     * @return {@code null}; the command offers no follow-up suggestions
     */
    @Override
    public String[] run(String[] args, int startIdx, Terminal terminal) {
        ModelScene scene = terminal.modelScene;
        if (scene == null || scene.modelCollection == null) {
            terminal.error("no collection is open; run the scene with -Dixdar.model=<directory>");
            return null;
        }
        ModelCollection collection = scene.modelCollection;
        String subcommand = args[startIdx].toLowerCase();
        switch (subcommand) {
            case LIST -> printMembers(collection, terminal);
            case NEXT -> scene.nextMember();
            case PREV -> scene.prevMember();
            case KEEP -> setKeep(scene, terminal, true);
            case REJECT -> setKeep(scene, terminal, false);
            default -> terminal.error(usage());
        }
        return null;
    }

    /**
     * Print one row per member: the cursor marker, the keep flag, the name, the sizes and the
     * settings summary.
     *
     * @param collection collection to print
     * @param terminal terminal receiving the rows
     */
    private void printMembers(ModelCollection collection, Terminal terminal) {
        terminal.history.addLine(collection.name + ": " + collection.memberCount() + " members, "
                + collection.keptCount() + " kept, manifest " + collection.manifestPath,
                Color.COMMAND);
        int current = collection.index();
        for (int member = 0; member < collection.memberCount(); member++) {
            boolean keep = collection.memberKeep[member];
            String settings = ModelCollection.settingsSummary(collection.memberSettings[member]);
            String row = (member == current ? "> " : "  ")
                    + (keep ? "[x] " : "[ ] ") + collection.memberNames[member]
                    + "  " + collection.countSummary(member)
                    + (settings.isEmpty() ? "" : "  " + settings);
            terminal.history.addLine(row, keep ? Color.BRIGHT_GREEN : Color.LIGHT_GRAY);
        }
    }

    /**
     * Set the current member's keep flag and rewrite the manifest.
     *
     * @param scene scene holding the collection
     * @param terminal terminal receiving the confirmation
     * @param keep {@code true} to keep the member, {@code false} to reject it
     */
    private void setKeep(ModelScene scene, Terminal terminal, boolean keep) {
        int member = scene.currentMemberIndex();
        if (member < 0) {
            terminal.error("the collection has no members");
            return;
        }
        ModelCollection collection = scene.modelCollection;
        if (collection.memberKeep[member] != keep) {
            scene.toggleKeepCurrentMember();
        }
        terminal.history.addLine(collection.memberNames[member]
                + (collection.memberKeep[member] ? " kept" : " rejected"), Color.COMMAND);
    }
}
