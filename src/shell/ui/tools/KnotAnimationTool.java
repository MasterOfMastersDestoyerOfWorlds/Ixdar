package shell.ui.tools;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;

import org.apache.commons.math3.util.Pair;

import io.humble.video.Codec;
import io.humble.video.Encoder;
import io.humble.video.MediaPacket;
import io.humble.video.MediaPicture;
import io.humble.video.Muxer;
import io.humble.video.MuxerFormat;
import io.humble.video.PixelFormat;
import io.humble.video.Rational;
import io.humble.video.awt.MediaPictureConverter;
import io.humble.video.awt.MediaPictureConverterFactory;
import shell.Toggle;
import shell.cameras.Camera2D;
import shell.cuts.CutMatch;
import shell.cuts.CutMatchList;
import shell.knot.Knot;
import shell.knot.Segment;
import shell.render.Clock;
import shell.render.color.Color;
import shell.render.text.HyperString;
import shell.terminal.commands.ScreenShotCommand;
import shell.ui.Canvas3D;
import shell.ui.Drawing;
import shell.ui.main.Main;

public class KnotAnimationTool extends Tool {

    public enum States {
        Forward,
        Backward;

        public boolean atOrAfter(States state) {
            return this.ordinal() >= state.ordinal();
        }

        public boolean before(States state) {
            return this.ordinal() < state.ordinal();
        }
    }

    public enum Metric {
        PathLength,
        IxdarSkipView
    }

    public States state = States.Forward;
    public CutMatchList displayCML;

    public Knot selectedKnot;
    private int cuttingLayer;
    private int matchingLayer;
    private float timeElapsed;
    private float timeStart;
    private Muxer muxer;
    MediaPictureConverter converter;
    private Encoder encoder;
    private MediaPicture picture;
    private MediaPacket packet;
    private long frameNumber;
    private boolean recording = false;
    private static final float LAYER_TIME = 0.6f;
    private static final float PAUSE_TIME = 0.2f;

    public KnotAnimationTool() {
        disallowedToggles = new Toggle[] { Toggle.DrawCutMatch, Toggle.CanSwitchLayer,
                Toggle.DrawKnotGradient, Toggle.DrawMetroDiagram, Toggle.DrawDisplayedKnots };
    }

    @Override
    public void reset() {
        super.reset();
        state = States.Forward;
        cuttingLayer = 0;
        matchingLayer = 1;
        timeStart = Clock.time();
        timeElapsed = 0;
        Main.updateKnotsDisplayed();
        try {
            if (Toggle.RecordKnotAnimation.value) {
                this.startRecording();
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        instruct();
    }

    @Override
    public void hoverChanged() {
    }

    @Override
    public void draw(Camera2D camera, float minLineThickness) {

        float animStep = timeElapsed / LAYER_TIME;
        if (animStep > 1) {
            animStep = 1;
        }
        HashMap<Long, Integer> colorLookup = Main.colorLookup;
        ArrayList<Color> colors = Main.knotGradientColors;
        boolean top = cuttingLayer == Main.totalLayers;
        ArrayList<Long> drawnSegments = new ArrayList<>();

        Drawing.setScaledStroke(camera);
        if (top) {
            for (Integer id : Main.knotLayerLookup.keySet()) {
            }
        } else {
            for (Integer id : Main.knotLayerLookup.keySet()) {
            }
        }
        for (Integer id : Main.knotLayerLookup.keySet()) {
            if (Main.knotLayerLookup.get(id) == matchingLayer) {
                Knot k = Main.resultKnots.stream().filter(resultKnot -> resultKnot.id == id).findFirst().get();
                ArrayList<Pair<Long, Long>> idTransform = Main.lookupPairs(k);
                for (int i = 0; i < k.manifoldSegments.size(); i++) {
                    Segment s = k.manifoldSegments.get(i);
                    Pair<Long, Long> lookUpPair = idTransform.get(i);
                    if (!drawnSegments.contains(s.id)) {
                        Drawing.drawGradientSegmentPartial(s,
                                colors.get(colorLookup.get(lookUpPair.getFirst())),
                                colors.get(colorLookup.get(lookUpPair.getSecond())),
                                animStep,
                                camera);
                    }
                }
            }
        }
        float currTime = Clock.time();
        timeElapsed = currTime - timeStart;
        if (timeElapsed > (LAYER_TIME + PAUSE_TIME)) {
            timeStart = currTime;
            timeElapsed = 0;
            cuttingLayer++;
            matchingLayer++;
            if (cuttingLayer > Main.totalLayers) {
                cuttingLayer = 0;
                matchingLayer = 1;
                if (recording) {
                    stopRecording();
                }
            }
        }
        if (recording) {
            recordFrame();
        }
    }

    public void startRecording() throws InterruptedException, IOException {
        final Rational framerate = Rational.make(1, 30);

        muxer = Muxer.make("./ree.mp4", null, "mp4");

        final MuxerFormat format = muxer.getFormat();
        final Codec codec;
        codec = Codec.findEncodingCodec(format.getDefaultVideoCodecId());

        encoder = Encoder.make(codec);

        encoder.setWidth(Canvas3D.frameBufferWidth);
        encoder.setHeight(Canvas3D.frameBufferHeight);
        // We are going to use 420P as the format because that's what most video formats
        // these days use
        final PixelFormat.Type pixelformat = PixelFormat.Type.PIX_FMT_YUV420P;
        encoder.setPixelFormat(pixelformat);
        encoder.setTimeBase(framerate);

        if (format.getFlag(MuxerFormat.Flag.GLOBAL_HEADER))
            encoder.setFlag(Encoder.Flag.FLAG_GLOBAL_HEADER, true);

        encoder.open(null, null);

        muxer.addNewStream(encoder);

        muxer.open(null, null);

        picture = MediaPicture.make(
                encoder.getWidth(),
                encoder.getHeight(),
                pixelformat);
        picture.setTimeBase(framerate);

        packet = MediaPacket.make();
        frameNumber = 0;
        recording = Toggle.RecordKnotAnimation.value;
    }

    public void recordFrame() {
        /** Make the screen capture && convert image to TYPE_3BYTE_BGR */

        BufferedImage img = ScreenShotCommand.printScreen();
        final BufferedImage screen = convertToType(img,
                BufferedImage.TYPE_3BYTE_BGR);

        /**
         * This is LIKELY not in YUV420P format, so we're going to convert it using some
         * handy utilities.
         */
        if (converter == null)
            converter = MediaPictureConverterFactory.createConverter(screen, picture);
        converter.toPicture(picture, screen, frameNumber);
        frameNumber++;
        do {
            encoder.encode(packet, picture);
            if (packet.isComplete())
                muxer.write(packet, false);
        } while (packet.isComplete());
    }

    public void stopRecording() {

        /**
         * Encoders, like decoders, sometimes cache pictures so it can do the right
         * key-frame optimizations. So, they need to be flushed as well. As with the
         * decoders, the convention is to pass in a null input until the output is not
         * complete.
         */
        do {
            encoder.encode(packet, null);
            if (packet.isComplete())
                muxer.write(packet, false);
        } while (packet.isComplete());

        /** Finally, let's clean up after ourselves. */
        muxer.close();
        recording = false;
    }

    public static BufferedImage convertToType(BufferedImage sourceImage,
            int targetType) {
        BufferedImage image;

        // if the source image is already the target type, return the source image

        if (sourceImage.getType() == targetType)
            image = sourceImage;

        // otherwise create a new image of the target type and draw the new
        // image

        else {
            image = new BufferedImage(sourceImage.getWidth(),
                    sourceImage.getHeight(), targetType);
            image.getGraphics().drawImage(sourceImage, 0, 0, null);
        }

        return image;
    }

    @Override
    public void click(Segment s, Knot kp, Knot cp) {
        confirm();
    }

    @Override
    public void confirm() {
    }

    public void instruct() {
        Main.terminal.instruct("Infinity lies at the bottom of the well.");
    }

    @Override
    public void increaseViewLayer() {
    }

    @Override
    public void decreaseViewLayer() {
    }

    @Override
    public void cycleToolLayerNext() {
    }

    @Override
    public void cycleToolLayerPrev() {
    }

    @Override
    public HyperString buildInfoText() {
        HyperString h = new HyperString();
        h.addLine("Layer: " + cuttingLayer);
        h.addLine("Tour Length: " + String.format("%.2f", Main.tourLength));
        h.wrap();
        return h;
    }

    @Override
    public String displayName() {
        return "Knot Anim";
    }

    @Override
    public String fullName() {
        return "knotanim";
    }

    @Override
    public String shortName() {
        return "ka";
    }

    @Override
    public String desc() {
        return "A tool that animates the calculations done on an ixdar file";
    }
}
