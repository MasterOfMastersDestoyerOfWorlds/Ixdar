package ixdar.scenes.trade.render;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Random;

import org.joml.Vector2f;

import ixdar.audio.AudioAssets;
import ixdar.game.trade.TradeGame;
import ixdar.game.trade.economy.Loop;
import ixdar.game.trade.map.City;
import ixdar.game.trade.map.Place;
import ixdar.game.trade.routes.Leg;
import ixdar.graphics.render.sdf.SDFCircle;
import ixdar.platform.Clock;
import ixdar.scenes.Scene;
import ixdar.scenes.trade.TradeScene;
import ixdar.scenes.trade.hud.Hud;

/**
 * The end-of-lap show: each loop's wagon runs its round, and where it sells, gold bursts out of the city
 * and is pulled into the gold counter, each coin clinking at a random pitch as it lands while the counter
 * counts up.
 */
public class LapAnimation {
    public static final float WAGON_SECONDS = 2.5f;
    public static final float COIN_SECONDS = 0.75f;
    public static final float BURST_SPEED = 420f;
    public static final float BURST_SHARE = 0.3f;
    public static final float COIN_RADIUS = 5f;
    public static final float WAGON_RADIUS_IN_CELLS = 0.2f;
    public static final double GOLD_PER_COIN = 6;
    public static final int MOST_COINS_PER_CITY = 14;
    public static final float MIN_PITCH = 0.9f;
    public static final float PITCH_SPREAD = 0.45f;
    public static final float PITCH_RISE_PER_COIN = 0.012f;
    public static final float CLINK_GAP_SECONDS = 0.03f;
    public static final float GOLD_TEXT_RISE = 50f;

    private final Random random = new Random();
    private final ArrayList<float[]> coins = new ArrayList<>();
    private final HashSet<City> paidOut = new HashSet<>();
    private final SDFCircle circle = new SDFCircle();
    private float started = -1;
    private float lastClink;
    private int landed;
    private double displayedGold;
    private double finalGold;

    /**
     * Start the show for the lap just settled.
     *
     * @param scene      the trade scene
     * @param goldBefore the player's gold before the lap
     */
    public void start(TradeScene scene, double goldBefore) {
        started = Clock.time();
        coins.clear();
        paidOut.clear();
        landed = 0;
        displayedGold = goldBefore;
        finalGold = scene.game.player.gold;
    }

    /**
     * End the show at once, as a new level does.
     */
    public void stop() {
        started = -1;
        coins.clear();
    }

    /**
     * The gold the counter shows: counting up as coins land, the real total otherwise.
     *
     * @param scene the trade scene
     * @return gold to show
     */
    public double counterGold(TradeScene scene) {
        return started < 0 ? scene.game.player.gold : displayedGold;
    }

    /**
     * Move each loop's wagon round its legs and, as it reaches a city it sold in, burst that city's coins.
     *
     * @param scene the trade scene
     */
    public void drawMap(TradeScene scene) {
        if (started < 0) {
            return;
        }
        TradeGame game = scene.game;
        float t = Math.min(1f, (Clock.time() - started) / WAGON_SECONDS);
        float wagonRadius = WAGON_RADIUS_IN_CELLS * game.map.grid.cellSpacing() * scene.scale();
        for (Loop loop : game.player.loops) {
            int legs = loop.stops.length;
            float along = t * legs;
            int legIndex = Math.min(legs - 1, (int) along);
            Leg leg = game.table.leg(loop.stops[legIndex], loop.stops[(legIndex + 1) % legs]);
            float step = (along - legIndex) * (leg.points.length - 1);
            int at = Math.min(leg.points.length - 2, (int) step);
            if (leg.points.length >= 2) {
                Vector2f from = scene.screen(leg.points[at]);
                Vector2f to = scene.screen(leg.points[at + 1]);
                circle.borderThickness = PathRenderer.CITY_BORDER_SHARE;
                circle.draw(new Vector2f(from).lerp(to, step - at), wagonRadius, scene.palette.text,
                        scene.palette.highlight, scene.mapCamera);
            }
            for (int k = 0; k <= legIndex; k++) {
                Place stop = loop.stops[(k + 1) % legs];
                if (stop instanceof City city && !paidOut.contains(city) && (float) (k + 1) / legs <= t + 1e-4f) {
                    paidOut.add(city);
                    double gold = city.lastSold.getOrDefault(game.player, 0) * city.lastPrice;
                    int count = (int) Math.min(MOST_COINS_PER_CITY, Math.ceil(gold / GOLD_PER_COIN));
                    Vector2f p = scene.placePixel(city);
                    for (int c = 0; c < count; c++) {
                        double angle = random.nextDouble() * Math.PI * 2;
                        float speed = BURST_SPEED * (0.5f + random.nextFloat());
                        coins.add(new float[] { p.x, p.y, (float) Math.cos(angle) * speed,
                            (float) Math.sin(angle) * speed, Clock.time() + c * CLINK_GAP_SECONDS,
                            (float) (gold / count) });
                    }
                }
            }
        }
        for (City city : paidOut) {
            double gold = city.lastSold.getOrDefault(game.player, 0) * city.lastPrice;
            if (gold > 0) {
                Vector2f p = scene.placePixel(city);
                scene.hud.text("+" + Math.round(gold) + " gold", p.x, p.y + GOLD_TEXT_RISE * (0.5f + t),
                        Hud.TEXT_HEIGHT, scene.palette.highlight);
            }
        }
        if (t >= 1f && coins.isEmpty()) {
            started = -1;
        }
    }

    /**
     * Fly the coins: a fast burst outward, then pulled into the gold counter; each one that lands adds
     * its gold and clinks, a little higher each time like a run of collected bolts.
     *
     * @param scene the trade scene
     */
    public void drawCoins(TradeScene scene) {
        Vector2f counter = scene.topBar.goldPosition;
        float now = Clock.time();
        for (int k = coins.size() - 1; k >= 0; k--) {
            float[] coin = coins.get(k);
            float age = (now - coin[4]) / COIN_SECONDS;
            if (age < 0) {
                continue;
            }
            if (age >= 1f) {
                coins.remove(k);
                displayedGold = Math.min(finalGold, displayedGold + coin[5]);
                landed++;
                if (now - lastClink >= CLINK_GAP_SECONDS) {
                    lastClink = now;
                    float pitch = MIN_PITCH + random.nextFloat() * PITCH_SPREAD + landed * PITCH_RISE_PER_COIN;
                    Scene.audioPlaySfx(AudioAssets.GOLD_CLINK_SFX_VARIANTS[random.nextInt(
                            AudioAssets.GOLD_CLINK_SFX_VARIANTS.length)], pitch);
                }
                if (coins.isEmpty()) {
                    displayedGold = finalGold;
                }
                continue;
            }
            float burst = Math.min(age / BURST_SHARE, 1f);
            float seconds = burst * BURST_SHARE * COIN_SECONDS;
            Vector2f out = new Vector2f(coin[0] + coin[2] * seconds * (1 - burst * 0.5f),
                    coin[1] + coin[3] * seconds * (1 - burst * 0.5f));
            float pull = Math.max(0, (age - BURST_SHARE) / (1 - BURST_SHARE));
            Vector2f at = out.lerp(counter, pull * pull);
            circle.borderThickness = PathRenderer.CITY_BORDER_SHARE;
            circle.draw(at, COIN_RADIUS, scene.palette.highlight, scene.palette.text, scene.mapCamera);
        }
    }
}
