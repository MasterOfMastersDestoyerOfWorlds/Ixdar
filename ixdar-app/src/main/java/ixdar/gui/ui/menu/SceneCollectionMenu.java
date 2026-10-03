package ixdar.gui.ui.menu;

import ixdar.graphics.render.color.Color;
import ixdar.scenes.model.ModelCollection;
import ixdar.scenes.model.ModelScene;

/**
 * The COLLECTION section of the model menu: the open collection's members with their keep flags and
 * sizes, clickable to load, plus the cycle and keep/reject actions. Drawn by {@link SceneModelMenu}
 * only while a collection is open.
 */
public final class SceneCollectionMenu {

    public static final String COLLECTION_HEADER = "COLLECTION";

    public static final String KEPT_MARKER = "[x] ";

    public static final String REJECTED_MARKER = "[ ] ";

    public static final String CURRENT_MARKER = "> ";

    public static final String OTHER_MARKER = "  ";

    public static final String PREV_LABEL = "[  previous member";

    public static final String NEXT_LABEL = "]  next member";

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
     * Append the section's rows to a menu box under construction. Nothing is appended when no
     * collection is open, so a scene pointed at a single mesh sees the menu it always had.
     *
     * @param box scroll box whose rows are being built this frame
     */
    public void append(MenuScrollBox box) {
        ModelCollection collection = scene.modelCollection;
        if (collection == null) {
            return;
        }
        box.addRow(COLLECTION_HEADER + " " + collection.name, Color.AMBER, null);
        box.addRow(collection.memberCount() + " members, " + collection.keptCount() + " kept",
                Color.LIGHT_GRAY, null);
        String shared = collection.sharedSettingsSummary();
        if (!shared.isEmpty()) {
            box.addRow(shared, Color.LIGHT_GRAY, null);
        }
        box.addRow(collection.manifestPath.toString(), Color.LIGHT_GRAY, null);

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
            box.addRow(row, color, () -> scene.loadMember(target));
        }

        box.addRow(PREV_LABEL, Color.BLUE_WHITE, () -> scene.prevMember());
        box.addRow(NEXT_LABEL, Color.BLUE_WHITE, () -> scene.nextMember());
        box.addRow(KEEP_LABEL, Color.BLUE_WHITE, () -> scene.toggleKeepCurrentMember());
        box.addRow("", Color.LIGHT_GRAY, null);
    }
}
