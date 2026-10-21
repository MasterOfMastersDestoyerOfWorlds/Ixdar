package ixdar.gui.ui.menu.model;

import ixdar.graphics.render.color.Color;
import ixdar.gui.ui.menu.MenuScrollBox;
import ixdar.models.ModelCollection;
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
     * Append the section's rows to a menu box under construction. Nothing is appended for a model
     * opened alone, so a scene pointed at a single mesh sees the menu it always had.
     *
     * @param box scroll box whose rows are being built this frame
     */
    public void append(MenuScrollBox box) {
        ModelCollection collection = scene.modelCollection;
        if (!collection.hasManifest()) {
            return;
        }
        box.addRow(COLLECTION_HEADER + " " + collection.name, Color.YELLOW_ORANGE, null);
        box.addRow(collection.memberCount() + " members, " + collection.keptCount() + " kept",
                Color.GREY_DARK, null);
        String shared = collection.sharedSettingsSummary();
        if (!shared.isEmpty()) {
            box.addRow(shared, Color.GREY_DARK, null);
        }
        box.addRow(collection.manifestPath.toString(), Color.GREY_DARK, null);

        int current = collection.index();
        for (int member = 0; member < collection.memberCount(); member++) {
            boolean isCurrent = member == current;
            boolean keep = collection.memberKeep[member];
            String row = (isCurrent ? CURRENT_MARKER : OTHER_MARKER)
                    + (keep ? KEPT_MARKER : REJECTED_MARKER)
                    + collection.members[member].displayName + "  " + collection.countSummary(member);
            String settings = ModelCollection.settingsSummary(collection.memberSettings[member]);
            if (shared.isEmpty() && !settings.isEmpty()) {
                row = row + "  " + settings;
            }
            Color color = isCurrent ? Color.GREEN_TEAL_LIGHT
                    : (keep ? Color.TEAL_DARK : Color.GREY_DARK);
            int target = member;
            box.addRow(row, color, () -> scene.loadMember(target));
        }

        box.addRow(PREV_LABEL, Color.BLUE_GREY, () -> scene.prevMember());
        box.addRow(NEXT_LABEL, Color.BLUE_GREY, () -> scene.nextMember());
        box.addRow(KEEP_LABEL, Color.BLUE_GREY, () -> scene.toggleKeepCurrentMember());
        box.addRow("", Color.GREY_DARK, null);
    }
}
