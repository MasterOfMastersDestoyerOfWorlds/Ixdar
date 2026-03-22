package ixdar.game;

import org.joml.Vector2f;

import ixdar.graphics.cameras.Camera2D;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.sdf.SDFLine;

/**
 * Represents a road between two cities in the network.
 * Roads are the possible routes the player can use to build trade routes.
 */
public class Road {
    public City from;
    public City to;
    public double distance;
    public boolean discovered;

    private static final Color ROAD_COLOR = Color.BLUE_WHITE;
    private static final Color DISCOVERED_ROAD_COLOR = Color.LIGHT_GRAY;

    public Road(City from, City to) {
        this.from = from;
        this.to = to;
        this.distance = from.getDistanceFrom(to);
        this.discovered = false;
    }

    /**
     * Get the other city on this road
     * @param city one of the cities on this road
     * @return the other city, or null if the given city is not on this road
     */
    public City getOther(City city) {
        if (city == from) return to;
        if (city == to) return from;
        return null;
    }

    /**
     * Draw this road
     * @param camera the camera for coordinate transformation
     * @param line the SDFLine to use for drawing
     */
    public void draw(Camera2D camera, SDFLine line) {
        float x1 = camera.pointTransformX(from.getX());
        float y1 = camera.pointTransformY(from.getY());
        float x2 = camera.pointTransformX(to.getX());
        float y2 = camera.pointTransformY(to.getY());

        Color color = discovered ? DISCOVERED_ROAD_COLOR : ROAD_COLOR;
        line.setStroke(2f, false);
        line.draw(new Vector2f(x1, y1), new Vector2f(x2, y2), color, camera);
    }

    @Override
    public String toString() {
        return "Road[" + from.name + " <-> " + to.name + "]";
    }
}