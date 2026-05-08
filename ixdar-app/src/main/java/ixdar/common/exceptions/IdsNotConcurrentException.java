package ixdar.common.exceptions;

/**
 * Thrown when two knot IDs are required to be concurrent (i.e. adjacent / on
 * the same run) but are not.
 */
public class IdsNotConcurrentException extends Exception {

    public int ID;

    public int ID2;

    /**
     * Construct with the two non-concurrent IDs.
     *
     * @param id first ID
     * @param id2 second ID
     */
    public IdsNotConcurrentException(int id, int id2) {
        this.ID = id;
        this.ID2 = id2;
    }

}
