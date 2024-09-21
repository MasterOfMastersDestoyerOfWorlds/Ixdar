package shell.enums;

public enum State {
    toKP1(true),
    toCP1(false),
    toKP2(true),
    toCP2(false),
    None(false);

    boolean isKnot;
    State opposite;

    State(boolean isKnot) {
        this.isKnot = isKnot;
    }

    static {
        toKP1.opposite = toKP2;
        toCP1.opposite = toCP2;
        toKP2.opposite = toKP1;
        toCP2.opposite = toCP1;
        None.opposite = None;

    }
}
