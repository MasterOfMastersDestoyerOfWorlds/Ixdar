package ixdar.graphics.render.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A scene's looks: the ones it offers, in the order M cycles them, the one picked, and the one a
 * tool draws in its place while it needs it. Code names a look by its id, as it names a control.
 */
public final class SurfaceLooks {

    /** The looks the scene offers, in cycle order. */
    public final List<SurfaceLook> registered = new ArrayList<>();

    /** The look picked with M, {@code ixdar-cli shading} or a multiview; {@code null} until the scene registers its looks. */
    public SurfaceLook picked;

    /** The look a tool draws in place of {@link #picked} while it works, or {@code null}. */
    public SurfaceLook override;

    /**
     * The look drawn now.
     *
     * @return the override while one is set, else the picked look
     */
    public SurfaceLook shown() {
        return override != null ? override : picked;
    }

    /**
     * The offered look after the picked one, wrapping round; the first when the picked look is
     * not offered.
     *
     * @return the look M picks next
     */
    public SurfaceLook next() {
        int at = registered.indexOf(picked);
        return registered.isEmpty() ? picked : registered.get((at + 1) % registered.size());
    }

    /**
     * The offered look with an id, ignoring case and surrounding space.
     *
     * @param id a look's id
     * @return the look
     * @throws IllegalArgumentException naming every offered look when none has that id
     */
    public SurfaceLook named(String id) {
        for (SurfaceLook look : registered) {
            if (look.id.equalsIgnoreCase(id.trim())) {
                return look;
            }
        }
        throw new IllegalArgumentException("shading must be one of " + names() + "; not '" + id
                + "'");
    }

    /**
     * Whether the picked look has an id.
     *
     * @param id a look's id
     * @return true while that look is picked
     */
    public boolean isPicked(String id) {
        return picked != null && picked.id.equals(id);
    }

    /**
     * Draw an offered look in place of the picked one until it is cleared.
     *
     * @param id the look's id
     * @throws IllegalArgumentException when the scene offers no look with that id
     */
    public void override(String id) {
        override = named(id);
    }

    /**
     * Stop drawing an override, only if it is still the one named: another tool's stays.
     *
     * @param id the id of the look the caller set
     */
    public void clearOverride(String id) {
        if (override != null && override.id.equals(id)) {
            override = null;
        }
    }

    /**
     * The offered ids in cycle order, as M's row and the automation replies list them.
     *
     * @return the ids joined by commas
     */
    public String names() {
        List<String> names = new ArrayList<>();
        for (SurfaceLook look : registered) {
            names.add(look.id);
        }
        return String.join(", ", names);
    }
}
