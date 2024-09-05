package shell.enums;

import shell.knot.Segment;
import shell.knot.VirtualPoint;

public class FindState {
    public enum States{
    None,
    FindStart,
    FirstSelected
    }

    public States state = States.None;

    public Segment hover;
    public VirtualPoint hoverKP;
    public VirtualPoint hoverCP;

    
    public Segment firstSelectedSegment;
    public VirtualPoint firstSelectedKP;
    public VirtualPoint firstSelectedCP;

    public void clearHover(){
        hover = null;
        hoverCP = null;
        hoverKP = null;
    }
    public void setHover(Segment s, VirtualPoint kp, VirtualPoint cp){
        hover = s;
        hoverKP = kp;
        hoverCP = cp;
    }

    public void setFirstSelected(Segment s, VirtualPoint kp, VirtualPoint cp){
        firstSelectedSegment = s;
        firstSelectedKP = kp;
        firstSelectedCP = cp;
        state = FindState.States.FirstSelected;
    }
    public void reset() {
        state= States.None;
        hover = null;
        hoverCP = null;
        hoverKP = null;
        firstSelectedSegment = null;
        firstSelectedKP = null;
        firstSelectedCP = null;
    }
}
