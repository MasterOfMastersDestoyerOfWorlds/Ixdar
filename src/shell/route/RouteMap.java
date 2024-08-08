package shell.route;

import java.util.HashMap;

public class RouteMap<T1, T2> extends HashMap<T1, T2> {
    @Override
    public String toString() {
        String str = "";
        for(T2 r : this.values()){
            str += r.toString() + "\n";
        }
        return str;
    }
}
