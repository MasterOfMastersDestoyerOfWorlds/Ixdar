package shell.ui;

public class Camera {

    public int Width, Height;
    public double ScaleFactor;
    public double PanX;
    public double PanY;
    public double defaultPanX;
    public double defaultPanY;

    public Camera(int Height, int Width, double ScaleFactor, double PanX, double PanY) {
        this.Height = Height;
        this.Width = Width;
        this.ScaleFactor = ScaleFactor;
        this.PanX = PanX;
        this.PanY = PanY;
    }

}