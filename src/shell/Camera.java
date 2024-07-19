package shell;

public class Camera {

    public int Width, Height;
    public double ScaleFactor;
    public double PanX;
    public double PanY;

    public Camera(int Height, int Width, double ScaleFactor, double PanX, double PanY) {
        this.Height = Height;
        this.Width = Width;
        this.ScaleFactor = ScaleFactor;
        this.PanX = PanX;
        this.PanY = PanY;
    }

}