package ixdar.geometry.point;

import java.util.Map;
import java.util.function.Supplier;

import ixdar.annotations.geometry.Geometry;
import ixdar.annotations.geometry.GeometryRegistry_Geometries;

public class GeometryMap {

    public static final Map<String, Supplier<? extends Geometry>> MAP;

    static {
        MAP = GeometryRegistry_Geometries.MAP;
    }
}
