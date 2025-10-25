package ixdar.common.exceptions;

import ixdar.geometry.shell.Range;

public class IdDoesNotExistException extends Exception {

    public int ID;

    public Range r;

    public IdDoesNotExistException(int id) {
        this.ID = id;
    }

    public IdDoesNotExistException(Range r) {
        this.r = r;
    }

}
