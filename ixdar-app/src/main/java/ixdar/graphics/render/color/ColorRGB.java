
package ixdar.graphics.render.color;

import org.joml.Vector3f;
import org.joml.Vector4f;

public class ColorRGB implements Color {
    public static final float NUM_0 = 0f;
    public static final float NUM_1 = 1f;
    public static final int NUM_255 = 255;
    public static final float NUM_255_2 = 255f;

    /** This value specifies the red component. */
    float red;

    /** This value specifies the green component. */
    float green;

    /** This value specifies the blue component. */
    float blue;

    /** This value specifies the transparency. */
    float alpha;

    String name;

    /** The default color is black. */
    public ColorRGB() {
        this(NUM_0, NUM_0, NUM_0);
    }

    /**
     * Creates a RGB-Color with an alpha value of 1.
     *
     * @param red   The red component. Range from 0f to 1f.
     * @param green The green component. Range from 0f to 1f.
     * @param blue  The blue component. Range from 0f to 1f.
     */
    public ColorRGB(float red, float green, float blue) {
        this(red, green, blue, NUM_1);
    }

    /**
     * TODO: document {@code ColorRGB}.
     *
     * @param red TODO: describe
     * @param green TODO: describe
     * @param blue TODO: describe
     * @param name TODO: describe
     */
    public ColorRGB(float red, float green, float blue, String name) {
        this(red, green, blue, NUM_1);
        this.name = name;
    }

    /**
     * Creates a RGBA-Color.
     *
     * @param red   The red component. Range from 0f to 1f.
     * @param green The green component. Range from 0f to 1f.
     * @param blue  The blue component. Range from 0f to 1f.
     * @param alpha The transparency. Range from 0f to 1f.
     */
    public ColorRGB(float red, float green, float blue, float alpha) {
        setRed(red);
        setGreen(green);
        setBlue(blue);
        setAlpha(alpha);
    }

    /**
     * TODO: document {@code ColorRGB}.
     *
     * @param red TODO: describe
     * @param green TODO: describe
     * @param blue TODO: describe
     * @param alpha TODO: describe
     * @param name TODO: describe
     */
    public ColorRGB(float red, float green, float blue, float alpha, String name) {
        setRed(red);
        setGreen(green);
        setBlue(blue);
        setAlpha(alpha);
        this.name = name;
    }

    /**
     * Creates a RGB-Color with an alpha value of 1.
     *
     * @param red   The red component. Range from 0 to 255.
     * @param green The green component. Range from 0 to 255.
     * @param blue  The blue component. Range from 0 to 255.
     */
    public ColorRGB(int red, int green, int blue) {
        this(red, green, blue, NUM_255);
    }

    /**
     * TODO: document {@code ColorRGB}.
     *
     * @param red TODO: describe
     * @param green TODO: describe
     * @param blue TODO: describe
     * @param name TODO: describe
     */
    public ColorRGB(int red, int green, int blue, String name) {
        this(red, green, blue, NUM_255);
        this.name = name;
    }

    /**
     * Creates a RGBA-Color.
     *
     * @param red   The red component. Range from 0 to 255.
     * @param green The green component. Range from 0 to 255.
     * @param blue  The blue component. Range from 0 to 255.
     * @param alpha The transparency. Range from 0 to 255.
     */
    public ColorRGB(int red, int green, int blue, int alpha) {
        setRed(red);
        setGreen(green);
        setBlue(blue);
        setAlpha(alpha);
    }

    /**
     * TODO: document {@code ColorRGB}.
     *
     * @param red TODO: describe
     * @param green TODO: describe
     * @param blue TODO: describe
     * @param alpha TODO: describe
     * @param string TODO: describe
     */
    public ColorRGB(int red, int green, int blue, int alpha, String string) {
        setRed(red);
        setGreen(green);
        setBlue(blue);
        setAlpha(alpha);
        this.name = string;
    }

    /**
     * TODO: document {@code ColorRGB}.
     *
     * @param color TODO: describe
     */
    public ColorRGB(Color color) {
        Vector4f other = color.toVector4f();
        setRed(other.x);
        setGreen(other.y);
        setBlue(other.z);
        setAlpha(other.w);
    }

    /**
     * TODO: document {@code ColorRGB}.
     *
     * @param color TODO: describe
     * @param alpha TODO: describe
     */
    public ColorRGB(Color color, float alpha) {
        Vector3f other = color.toVector3f();
        red = other.x;
        green = other.y;
        blue = other.z;
        this.alpha = alpha;
    }

    /**
     * Returns the red component.
     *
     * @return The red component.
     */
    public float getRed() {
        return red;
    }

    /**
     * Sets the red component.
     *
     * @param red The red component. Range from 0f to 1f.
     */
    public void setRed(float red) {
        if (red < NUM_0) {
            red = NUM_0;
        }
        if (red > NUM_1) {
            red = NUM_1;
        }
        this.red = red;
    }

    /**
     * Sets the red component.
     *
     * @param red The red component. Range from 0 to 255.
     */
    public void setRed(int red) {
        setRed(red / NUM_255_2);
    }

    /**
     * Returns the green component.
     *
     * @return The green component.
     */
    public float getGreen() {
        return green;
    }

    /**
     * Sets the green component.
     *
     * @param green The green component. Range from 0f to 1f.
     */
    public void setGreen(float green) {
        if (green < NUM_0) {
            green = NUM_0;
        }
        if (green > NUM_1) {
            green = NUM_1;
        }
        this.green = green;
    }

    /**
     * Sets the green component.
     *
     * @param green The green component. Range from 0 to 255.
     */
    public void setGreen(int green) {
        setGreen(green / NUM_255_2);
    }

    /**
     * Returns the blue component.
     *
     * @return The blue component.
     */
    public float getBlue() {
        return blue;
    }

    /**
     * Sets the blue component.
     *
     * @param blue The blue component. Range from 0f to 1f.
     */
    public void setBlue(float blue) {
        if (blue < NUM_0) {
            blue = NUM_0;
        }
        if (blue > NUM_1) {
            blue = NUM_1;
        }
        this.blue = blue;
    }

    /**
     * Sets the blue component.
     *
     * @param blue The blue component. Range from 0 to 255.
     */
    public void setBlue(int blue) {
        setBlue(blue / NUM_255_2);
    }

    /**
     * Returns the transparency.
     *
     * @return The transparency.
     */
    public float getAlpha() {
        return alpha;
    }

    /**
     * Sets the transparency.
     *
     * @param alpha The transparency. Range from 0f to 1f.
     */
    public void setAlpha(float alpha) {
        if (alpha < NUM_0) {
            alpha = NUM_0;
        }
        if (alpha > NUM_1) {
            alpha = NUM_1;
        }
        this.alpha = alpha;
    }

    /**
     * Sets the transparency.
     *
     * @param alpha The transparency. Range from 0 to 255.
     */
    public void setAlpha(int alpha) {
        setAlpha(alpha / NUM_255_2);
    }

    /**
     * Returns the color as a (x,y,z)-Vector.
     *
     * @return The color as vec3.
     */
    @Override
    public Vector3f toVector3f() {
        return new Vector3f(red, green, blue);
    }

    /**
     * Returns the color as a (x,y,z,w)-Vector.
     *
     * @return The color as vec4.
     */
    @Override
    public Vector4f toVector4f() {
        return new Vector4f(red, green, blue, alpha);
    }

    /**
     * TODO: document {@code toString}.
     *
     * @return TODO: describe
     */
    @Override
    public String toString() {
        if (!name.isEmpty()) {
            return name;
        } else {
            return "R: " + red + " G: " + green + " B: " + blue + " A: " + alpha;
        }
    }

    /**
     * TODO: document {@code getName}.
     *
     * @return TODO: describe
     */
    @Override
    public String getName() {
        return name;
    }

}
