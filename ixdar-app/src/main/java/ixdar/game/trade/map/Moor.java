package ixdar.game.trade.map;

import java.util.HashSet;

/**
 * Impassable ground: no route may enter its cells.
 */
public class Moor {
    public final HashSet<Integer> cells = new HashSet<>();

    /**
     * Whether a cell is moor.
     *
     * @param cell a cell index
     * @return true when blocked
     */
    public boolean blocks(int cell) {
        return cells.contains(cell);
    }
}
