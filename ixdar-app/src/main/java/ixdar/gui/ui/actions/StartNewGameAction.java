package ixdar.gui.ui.actions;

import java.util.ArrayList;
import java.util.Random;

import ixdar.canvas.Canvas3D;
import ixdar.game.City;
import ixdar.game.CityNetwork;
import ixdar.geometry.point.Grid.CartesianGrid;
import ixdar.scenes.trade.TradeScene;

/**
 * Action to start a new trade game with randomized cities.
 */
public class StartNewGameAction implements Action {
    public static final float ROAD_PROXIMITY_THRESHOLD = 200f;
    public static final int MIN_CITY_COUNT = 8;
    public static final int MAX_EXTRA_CITIES = 5;
    public static final float MAP_WIDTH = 800f;
    public static final float MAP_HEIGHT = 600f;
    public static final float MAP_MARGIN = 100f;
    public static final int MIN_POPULATION = 1000;
    public static final int POPULATION_RANGE = 9000;
    public static final int PRODUCTION_RANGE = 10;
    public static final int MIN_PRODUCTION = 5;
    public static final int MIN_CONSUMPTION = 3;
    public static final int CONSUMPTION_RANGE = 7;
    @Override
    public void perform() {
        ArrayList<City> cities = generateRandomCities();
        CityNetwork network = new CityNetwork(cities, new CartesianGrid());
        network.generateRoadsFromProximity(ROAD_PROXIMITY_THRESHOLD);
        TradeScene.startNewGame(network, Canvas3D.instance);
    }

    /**
     * Generate a set of random cities for the game. In the future, this will load
     * from city JSON files (TRADE-17).
     */
    private ArrayList<City> generateRandomCities() {
        ArrayList<City> cities = new ArrayList<>();
        Random random = new Random();

        // Generate 8-12 random cities
        int numCities = MIN_CITY_COUNT + random.nextInt(MAX_EXTRA_CITIES);

        String[] cityNames = new String[] {
                "Port Royal", "Kingston", "Nassau", "Havana", "Tortuga",
                "Cartagena", "San Juan", "Barbados", "Trinidad", "Martinique",
                "Santo Domingo", "Santiago", "Vera Cruz", "Panama City", "Portobelo"
        };
        String[][] resourcePairs = new String[][] {
                { "sugar", "rum" },
                { "tobacco", "cigars" },
                { "cotton", "textiles" },
                { "coffee", "spices" },
                { "lumber", "ships" },
                { "grain", "bread" },
                { "ore", "tools" },
                { "fish", "salt" }
        };

        // Spread cities across a reasonable map area
        float mapWidth = MAP_WIDTH;
        float mapHeight = MAP_HEIGHT;
        float margin = MAP_MARGIN;

        for (int i = 0; i < numCities && i < cityNames.length; i++) {
            float x = margin + random.nextFloat() * (mapWidth - 2 * margin);
            float y = margin + random.nextFloat() * (mapHeight - 2 * margin);
            int population = MIN_POPULATION + random.nextInt(POPULATION_RANGE);

            City city = new City(
                    cityNames[i].toLowerCase().replace(" ", "_"),
                    cityNames[i],
                    x,
                    y,
                    population);

            // Add some resources
            String[] resources = resourcePairs[i % resourcePairs.length];
            city.addProduction(resources[0], MIN_PRODUCTION + random.nextInt(PRODUCTION_RANGE));
            city.addConsumption(resources[1], MIN_CONSUMPTION + random.nextInt(CONSUMPTION_RANGE));

            cities.add(city);
        }

        return cities;
    }

}
