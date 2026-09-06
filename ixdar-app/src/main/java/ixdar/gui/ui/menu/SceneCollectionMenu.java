package ixdar.gui.ui.menu;

import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.text.HyperString;
import ixdar.scenes.model.ModelCollection;
import ixdar.scenes.model.ModelScene;

/**
 * The COLLECTION section of the ESC menu: the open collection's members with their keep flags and
 * sizes, clickable to load, plus the cycle and keep/reject actions. Drawn by {@link SceneModelMenu}
 * only while a collection is open.
 */
public final class SceneCollectionMenu {

    /** Header above the collection's members. */
    public static final String COLLECTION_HEADER = "COLLECTION";

    /** Marker prefixed to a kept member. */
    public static final String KEPT_MARKER = "[x] ";

    /** Marker prefixed to a rejected member. */
    public static final String REJECTED_MARKER = "[ ] ";

    /** Marker prefixed to the member under the cursor. */
    public static final String CURRENT_MARKER = "> ";

    /** Marker prefixed to members not under the cursor, keeping columns aligned. */
    public static final String OTHER_MARKER = "  ";

    /** Label of the previous-member action. */
    public static final String PREV_LABEL = "[  previous member";

    /** Label of the next-member action. */
    public static final String NEXT_LABEL = "]  next member";

    /** Label of the keep/reject action. */
    public static final String KEEP_LABEL = "K  keep / reject this member";

    private final ModelScene scene;

    /**
     * Bind the section to the scene whose collection it shows.
     *
     * @param scene scene holding the open collection
     */
    public SceneCollectionMenu(ModelScene scene) {
        this.scene = scene;
    }

    /**
     * Append the section to a menu under construction. Nothing is appended when no collection is
     * open, so a scene pointed at a single mesh sees the menu it always had.
     *
     * @param hyper menu text being built this frame
     */
    public void append(HyperString hyper) {
        ModelCollection collection = scene.modelCollection;
        if (collection == null) {
            return;
        }
        hyper.addLine(COLLECTION_HEADER + " " + collection.name, Color.AMBER);
        hyper.addLine(collection.memberCount() + " members, " + collection.keptCount() + " kept",
                Color.LIGHT_GRAY);
        String shared = collection.sharedSettingsSummary();
        if (!shared.isEmpty()) {
            hyper.addLine(shared, Color.LIGHT_GRAY);
        }
        hyper.addLine(collection.manifestPath.toString(), Color.LIGHT_GRAY);

        int current = collection.index();
        for (int member = 0; member < collection.memberCount(); member++) {
            boolean isCurrent = member == current;
            boolean keep = collection.memberKeep[member];
            String row = (isCurrent ? CURRENT_MARKER : OTHER_MARKER)
                    + (keep ? KEPT_MARKER : REJECTED_MARKER)
                    + collection.memberNames[member] + "  " + collection.countSummary(member);
            String settings = ModelCollection.settingsSummary(collection.memberSettings[member]);
            if (shared.isEmpty() && !settings.isEmpty()) {
                row = row + "  " + settings;
            }
            Color color = isCurrent ? Color.BRIGHT_GREEN
                    : (keep ? Color.COMMAND : Color.LIGHT_GRAY);
            int target = member;
            hyper.addWordClick(row, color, () -> scene.loadMember(target));
            hyper.newLine();
        }

        hyper.addWordClick(PREV_LABEL, Color.BLUE_WHITE, () -> scene.prevMember());
        hyper.newLine();
        hyper.addWordClick(NEXT_LABEL, Color.BLUE_WHITE, () -> scene.nextMember());
        hyper.newLine();
        hyper.addWordClick(KEEP_LABEL, Color.BLUE_WHITE, () -> scene.toggleKeepCurrentMember());
        hyper.newLine();
        hyper.newLine();
    }
}
