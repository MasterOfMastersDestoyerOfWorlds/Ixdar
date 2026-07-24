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
    public static final float NUM_200 = 200f;
    public static final int NUM_8 = 8;
    public static final int NUM_5 = 5;
    public static final float NUM_800 = 800f;
    public static final float NUM_600 = 600f;
    public static final float NUM_100 = 100f;
    public static final int NUM_1000 = 1000;
    public static final int NUM_9000 = 9000;
    public static final int NUM_10 = 10;
    public static final int NUM_3 = 3;
    public static final int NUM_7 = 7;
    @Override
    public void perform() {
        ArrayList<City> cities = generateRandomCities();
        CityNetwork network = new CityNetwork(cities, new CartesianGrid());
        network.generateRoadsFromProximity(NUM_200);
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
        int numCities = NUM_8 + random.nextInt(NUM_5);

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
        float mapWidth = NUM_800;
        float mapHeight = NUM_600;
        float margin = NUM_100;

        for (int i = 0; i < numCities && i < cityNames.length; i++) {
            float x = margin + random.nextFloat() * (mapWidth - 2 * margin);
            float y = margin + random.nextFloat() * (mapHeight - 2 * margin);
            int population = NUM_1000 + random.nextInt(NUM_9000);

            City city = new City(
                    cityNames[i].toLowerCase().replace(" ", "_"),
                    cityNames[i],
                    x,
                    y,
                    population);

            // Add some resources
            String[] resources = resourcePairs[i % resourcePairs.length];
            city.addProduction(resources[0], NUM_5 + random.nextInt(NUM_10));
            city.addConsumption(resources[1], NUM_3 + random.nextInt(NUM_7));

            cities.add(city);
        }

        return cities;
    }

}
