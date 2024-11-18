package shell.exceptions;

public class IdDoesNotExistException extends Exception {

    public int ID;

    public IdDoesNotExistException(int id) {
        this.ID = id;
    }

}
