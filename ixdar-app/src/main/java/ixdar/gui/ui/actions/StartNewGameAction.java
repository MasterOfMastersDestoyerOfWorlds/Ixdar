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
    @Override
    public void perform() {
        ArrayList<City> cities = generateRandomCities();
        CityNetwork network = new CityNetwork(cities, new CartesianGrid());
        network.generateRoadsFromProximity(200f);
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
        int numCities = 8 + random.nextInt(5);

        String[] cityNames = cityNames();
        String[][] resourcePairs = resourcePairs();

        // Spread cities across a reasonable map area
        float mapWidth = 800f;
        float mapHeight = 600f;
        float margin = 100f;

        for (int i = 0; i < numCities && i < cityNames.length; i++) {
            float x = margin + random.nextFloat() * (mapWidth - 2 * margin);
            float y = margin + random.nextFloat() * (mapHeight - 2 * margin);
            int population = 1000 + random.nextInt(9000);

            City city = new City(
                    cityNames[i].toLowerCase().replace(" ", "_"),
                    cityNames[i],
                    x,
                    y,
                    population);

            // Add some resources
            String[] resources = resourcePairs[i % resourcePairs.length];
            city.addProduction(resources[0], 5 + random.nextInt(10));
            city.addConsumption(resources[1], 3 + random.nextInt(7));

            cities.add(city);
        }

        return cities;
    }

    private String[] cityNames() {
        return new String[] {
                "Port Royal", "Kingston", "Nassau", "Havana", "Tortuga",
                "Cartagena", "San Juan", "Barbados", "Trinidad", "Martinique",
                "Santo Domingo", "Santiago", "Vera Cruz", "Panama City", "Portobelo"
        };
    }

    private String[][] resourcePairs() {
        return new String[][] {
                { "sugar", "rum" },
                { "tobacco", "cigars" },
                { "cotton", "textiles" },
                { "coffee", "spices" },
                { "lumber", "ships" },
                { "grain", "bread" },
                { "ore", "tools" },
                { "fish", "salt" }
        };
    }

}
