
package shell.render.color;

import org.joml.Vector3f;
import org.joml.Vector4f;

public class ColorRGB implements Color {

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
        this(0f, 0f, 0f);
    }

    /**
     * Creates a RGB-Color with an alpha value of 1.
     *
     * @param red
     *              The red component. Range from 0f to 1f.
     * @param green
     *              The green component. Range from 0f to 1f.
     * @param blue
     *              The blue component. Range from 0f to 1f.
     */
    public ColorRGB(float red, float green, float blue) {
        this(red, green, blue, 1f);
    }

    /**
     * Creates a RGBA-Color.
     *
     * @param red
     *              The red component. Range from 0f to 1f.
     * @param green
     *              The green component. Range from 0f to 1f.
     * @param blue
     *              The blue component. Range from 0f to 1f.
     * @param alpha
     *              The transparency. Range from 0f to 1f.
     */
    public ColorRGB(float red, float green, float blue, float alpha) {
        setRed(red);
        setGreen(green);
        setBlue(blue);
        setAlpha(alpha);
    }

    /**
     * Creates a RGB-Color with an alpha value of 1.
     *
     * @param red
     *              The red component. Range from 0 to 255.
     * @param green
     *              The green component. Range from 0 to 255.
     * @param blue
     *              The blue component. Range from 0 to 255.
     */
    public ColorRGB(int red, int green, int blue) {
        this(red, green, blue, 255);
    }

    /**
     * Creates a RGBA-Color.
     *
     * @param red
     *              The red component. Range from 0 to 255.
     * @param green
     *              The green component. Range from 0 to 255.
     * @param blue
     *              The blue component. Range from 0 to 255.
     * @param alpha
     *              The transparency. Range from 0 to 255.
     */
    public ColorRGB(int red, int green, int blue, int alpha) {
        setRed(red);
        setGreen(green);
        setBlue(blue);
        setAlpha(alpha);
    }

    public ColorRGB(Color color) {
        Vector4f other = color.toVector4f();
        setRed(other.x);
        setGreen(other.y);
        setBlue(other.z);
        setAlpha(other.w);
    }

    public ColorRGB(Color color, float alpha) {
        Vector3f other = color.toVector3f();
        setRed(other.x);
        setGreen(other.y);
        setBlue(other.z);
        setAlpha(alpha);
    }

    public ColorRGB(float r, float g, float b, String name) {
        setRed(red);
        setGreen(green);
        setBlue(blue);
        setAlpha(alpha);
        this.name = name;
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
     * @param red
     *            The red component. Range from 0f to 1f.
     */
    public void setRed(float red) {
        if (red < 0f) {
            red = 0f;
        }
        if (red > 1f) {
            red = 1f;
        }
        this.red = red;
    }

    /**
     * Sets the red component.
     *
     * @param red
     *            The red component. Range from 0 to 255.
     */
    public void setRed(int red) {
        setRed(red / 255f);
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
     * @param green
     *              The green component. Range from 0f to 1f.
     */
    public void setGreen(float green) {
        if (green < 0f) {
            green = 0f;
        }
        if (green > 1f) {
            green = 1f;
        }
        this.green = green;
    }

    /**
     * Sets the green component.
     *
     * @param green
     *              The green component. Range from 0 to 255.
     */
    public void setGreen(int green) {
        setGreen(green / 255f);
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
     * @param blue
     *             The blue component. Range from 0f to 1f.
     */
    public void setBlue(float blue) {
        if (blue < 0f) {
            blue = 0f;
        }
        if (blue > 1f) {
            blue = 1f;
        }
        this.blue = blue;
    }

    /**
     * Sets the blue component.
     *
     * @param blue
     *             The blue component. Range from 0 to 255.
     */
    public void setBlue(int blue) {
        setBlue(blue / 255f);
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
     * @param alpha
     *              The transparency. Range from 0f to 1f.
     */
    public void setAlpha(float alpha) {
        if (alpha < 0f) {
            alpha = 0f;
        }
        if (alpha > 1f) {
            alpha = 1f;
        }
        this.alpha = alpha;
    }

    /**
     * Sets the transparency.
     *
     * @param alpha
     *              The transparency. Range from 0 to 255.
     */
    public void setAlpha(int alpha) {
        setAlpha(alpha / 255f);
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

    @Override
    public String toString() {
        if (!name.isEmpty()) {
            return name;
        } else {
            return "R: " + red + " G: " + green + " B: " + blue + " A: " + alpha;
        }
    }

}
