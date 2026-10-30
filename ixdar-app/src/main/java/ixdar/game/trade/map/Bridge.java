package ixdar.game.trade.map;

import org.joml.Vector2f;

/**
 * A bridge over a river: it opens the one step between two cells that the river separates, and stands on
 * the river side that step crosses, pointing along the step.
 */
public class Bridge {
    public final River river;
    public final int fromCell;
    public final int toCell;
    /** Where the step from centre to centre crosses the shared side, so the bridge sits on the road. */
    public final Vector2f position;
    /** Unit direction of the crossing, from {@link #fromCell} towards {@link #toCell}. */
    public final Vector2f direction;
    /** Length of the river side the bridge stands on, which sets how wide the bridge is drawn. */
    public final float sideLength;

    /**
     * A bridge over the step between two cells the river separates.
     *
     * @param river    the river it crosses
     * @param fromCell the cell on one bank
     * @param toCell   the neighbouring cell on the other bank
     */
    public Bridge(River river, int fromCell, int toCell) {
        this.river = river;
        this.fromCell = fromCell;
        this.toCell = toCell;
        int[] side = river.grid.sharedSide(fromCell, toCell);
        Vector2f a = river.grid.anchorPoints().get(side[0]);
        Vector2f b = river.grid.anchorPoints().get(side[1]);
        Vector2f from = river.grid.dualPoints().get(fromCell);
        Vector2f step = new Vector2f(river.grid.dualPoints().get(toCell)).sub(from);
        Vector2f along = new Vector2f(b).sub(a);
        float turn = step.x * along.y - step.y * along.x;
        float share = turn == 0 ? 0.5f : ((a.x - from.x) * along.y - (a.y - from.y) * along.x) / turn;
        position = new Vector2f(from).add(new Vector2f(step).mul(share));
        sideLength = a.distance(b);
        direction = step.normalize();
    }

    /**
     * Whether this bridge carries the step between two cells, either way.
     *
     * @param a one cell
     * @param b the other
     * @return true for this bridge's step
     */
    public boolean opens(int a, int b) {
        return (a == fromCell && b == toCell) || (a == toCell && b == fromCell);
    }
}
