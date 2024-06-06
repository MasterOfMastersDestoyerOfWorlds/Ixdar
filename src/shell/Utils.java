package shell;

import java.util.ArrayList;

import org.apache.commons.math3.util.Pair;

public final class Utils {

    public static <K, V> String pairsToString(ArrayList<Pair<K, V>> pairs) {
        String str = "[";
        for (Pair<K, V> p : pairs) {
            str += Utils.pairToString(p) + ",";
        }
        str += "]";
        return str;
    
    }

    public static <K, V> String pairToString(Pair<K, V> pair) {
        return "Pair[" + pair.getFirst() + " : " + pair.getSecond() + "]";
    
    }

    public static <K> String printArray(K[] array){
        String str = "[";
        for(K entry: array){
            str += entry + ", ";
        }
        str += "]";
        return str;
    }
    
}
