package shell.enums;

public enum State {
    toKP1{
        @Override
        public boolean isKnot() {
            return true;
        }
        
        @Override
        public State opposite() {
            return State.toKP2;
        }
    },
    toCP1{
        @Override
        public boolean isKnot() {
            return false;
        }
        
        @Override
        public State opposite() {
            return State.toCP2;
        }
    },
    toKP2{
        @Override
        public boolean isKnot() {
            return true;
        }
        
        @Override
        public State opposite() {
            return State.toKP1;
        }
    },
    toCP2{
        @Override
        public boolean isKnot() {
            return false;
        }   
        @Override
        public State opposite() {
            return State.toCP1;
        }
    },
    None{
        @Override
        public boolean isKnot() {
            return false;
        }
        @Override
        public State opposite() {
            return State.None;
        }
    };

    public abstract boolean isKnot();

    

    public abstract State opposite();
}

