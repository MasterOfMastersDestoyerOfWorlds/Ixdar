package shell;

import java.util.ArrayList;

import org.apache.commons.collections4.map.MultiKeyMap;
import org.apache.commons.math3.util.Pair;

public interface FixedCutInterface {
    
    public CutMatchList findCutMatchListFixedCut()
            throws SegmentBalanceException;
}
