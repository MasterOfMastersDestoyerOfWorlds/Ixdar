package ixdar.common.exceptions;

public class IdsNotConcurrentException extends Exception {

    public int ID;

    public int ID2;

    public IdsNotConcurrentException(int id, int id2) {
        this.ID = id;
        this.ID2 = id2;
    }

}
