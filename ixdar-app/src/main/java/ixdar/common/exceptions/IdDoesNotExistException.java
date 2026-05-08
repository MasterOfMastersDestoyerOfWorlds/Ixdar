package ixdar.common.exceptions;

import ixdar.geometry.shell.Range;

public class IdDoesNotExistException extends Exception {

    public int ID;

    public Range r;

    /**
     * TODO: document {@code IdDoesNotExistException}.
     *
     * @param id TODO: describe
     */
    public IdDoesNotExistException(int id) {
        this.ID = id;
    }

    /**
     * TODO: document {@code IdDoesNotExistException}.
     *
     * @param r TODO: describe
     */
    public IdDoesNotExistException(Range r) {
        this.r = r;
    }

}
