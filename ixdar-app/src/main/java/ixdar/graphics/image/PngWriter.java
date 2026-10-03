package ixdar.graphics.image;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Encodes a {@link PixelImage} as a PNG. Output is 8-bit truecolour without an alpha channel,
 * which is what every caller here writes; alpha in the source is discarded.
 *
 * See also: PNG specification, ISO/IEC 15948:2004, sections 11 and 12.
 */
public final class PngWriter {

    public static final byte[] SIGNATURE = {
        (byte) 0x89, 'P', 'N', 'G', '\r', '\n', (byte) 0x1A, '\n'
    };

    public static final int BIT_DEPTH = 8;

    public static final int COLOR_TYPE_RGB = 2;

    public static final int BYTES_PER_PIXEL = 3;

    public static final int IHDR_LENGTH = 13;

    public static final int FILTER_NONE = 0;

    public static final int BYTE_MASK = 0xFF;

    public static final int RED_SHIFT = 16;

    public static final int GREEN_SHIFT = 8;

    public static final int OCTET_BITS = 8;

    public static final int FIELD_BYTES = 4;

    private PngWriter() {
    }

    /**
     * Encode an image as a complete PNG file.
     *
     * @param image the pixels to encode
     * @return the PNG file contents
     * @throws IOException if assembling the byte stream fails
     */
    public static byte[] toBytes(PixelImage image) throws IOException {
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        png.write(SIGNATURE);

        ByteArrayOutputStream header = new ByteArrayOutputStream(IHDR_LENGTH);
        writeInt(header, image.width);
        writeInt(header, image.height);
        header.write(BIT_DEPTH);
        header.write(COLOR_TYPE_RGB);
        header.write(0);
        header.write(0);
        header.write(0);
        writeChunk(png, "IHDR", header.toByteArray());

        writeChunk(png, "IDAT", deflate(rawScanlines(image)));
        writeChunk(png, "IEND", new byte[0]);
        return png.toByteArray();
    }

    /**
     * Encode an image and write it to a file, creating missing parent directories.
     *
     * @param image the pixels to encode
     * @param file destination path
     * @throws IOException if encoding or writing fails
     */
    public static void write(PixelImage image, File file) throws IOException {
        File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        Files.write(file.toPath(), toBytes(image));
    }

    /**
     * Lay the image out as PNG scanlines, each preceded by its filter-type byte.
     *
     * @param image the pixels to lay out
     * @return the unfiltered scanline bytes ready for compression
     */
    private static byte[] rawScanlines(PixelImage image) {
        int strideBytes = 1 + image.width * BYTES_PER_PIXEL;
        byte[] raw = new byte[strideBytes * image.height];
        for (int y = 0; y < image.height; y++) {
            int out = y * strideBytes;
            raw[out++] = FILTER_NONE;
            int row = y * image.width;
            for (int x = 0; x < image.width; x++) {
                int pixel = image.argb[row + x];
                raw[out++] = (byte) ((pixel >> RED_SHIFT) & BYTE_MASK);
                raw[out++] = (byte) ((pixel >> GREEN_SHIFT) & BYTE_MASK);
                raw[out++] = (byte) (pixel & BYTE_MASK);
            }
        }
        return raw;
    }

    /**
     * Compress scanline bytes into the zlib stream an IDAT chunk carries.
     *
     * @param raw the unfiltered scanline bytes
     * @return the zlib-compressed bytes
     * @throws IOException if the output buffer rejects a write
     */
    private static byte[] deflate(byte[] raw) throws IOException {
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        deflater.setInput(raw);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length / 2 + IHDR_LENGTH);
        byte[] chunk = new byte[Short.MAX_VALUE];
        while (!deflater.finished()) {
            out.write(chunk, 0, deflater.deflate(chunk));
        }
        deflater.end();
        return out.toByteArray();
    }

    /**
     * Append one length-prefixed, CRC-suffixed PNG chunk.
     *
     * @param out stream collecting the file
     * @param type the four-character chunk type
     * @param data the chunk payload
     * @throws IOException if the stream rejects a write
     */
    private static void writeChunk(OutputStream out, String type, byte[] data) throws IOException {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        writeInt(out, data.length);
        out.write(typeBytes);
        out.write(data);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        writeInt(out, (int) crc.getValue());
    }

    /**
     * Append a big-endian 32-bit field.
     *
     * @param out stream collecting the file
     * @param value the value to append
     * @throws IOException if the stream rejects a write
     */
    private static void writeInt(OutputStream out, int value) throws IOException {
        for (int octet = FIELD_BYTES - 1; octet >= 0; octet--) {
            out.write((value >> (octet * OCTET_BITS)) & BYTE_MASK);
        }
    }
}
