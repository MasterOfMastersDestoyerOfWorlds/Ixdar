package ixdar.common.exceptions;

import ixdar.geometry.shell.Range;

/**
 * Thrown when a lookup by knot ID (or by an ID range) fails to resolve to an
 * existing entity. Either {@link #ID} or {@link #r} is populated depending on
 * which constructor was used.
 */
public class IdDoesNotExistException extends Exception {

    public int ID;

    public Range r;

    /**
     * Construct for a single missing ID.
     *
     * @param id the ID that could not be resolved
     */
    public IdDoesNotExistException(int id) {
        this.ID = id;
    }

    /**
     * Construct for a missing ID range.
     *
     * @param r range of IDs that could not be resolved
     */
    public IdDoesNotExistException(Range r) {
        this.r = r;
    }

}
