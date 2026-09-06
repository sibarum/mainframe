// Not part of the build. Run by hand when the mark changes:
//   java mainframe-dist/src/main/native/MakeIcon.java mainframe-dist/src/main/native/out
// then copy icon-*.png to mainframe-vexel-gui/src/main/resources/dev/mainframe/gui/desktop/ as
// mainframe-*.png, and mainframe.ico here beside the .rc.
// It is here rather than in a build plugin because it runs once a redesign, and a build step that
// rasterises identical PNGs on every compile is a slower build for no decision anybody makes.

import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/** One-off: rasterise the a-mainframe mark to PNGs and assemble a Windows .ico. */
public class MakeIcon {

    // vexelray-icons/svg/mainframe.svg, viewBox 0 0 96 96, colour #3fc06a. A body with two eyes and two
    // slots knocked out of it — the SVG does that with a mask, which is an Area subtraction here. The
    // pupils are knocked back <em>in</em>, so the order of these operations is the drawing.
    static final Color HUE = new Color(0x3f, 0xc0, 0x6a);
    static final int[] SIZES = {16, 32, 48, 256};

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        Files.createDirectories(out);
        List<byte[]> dibs = new ArrayList<>();
        for (int n : SIZES) {
            BufferedImage img = render(n);
            ImageIO.write(img, "PNG", out.resolve("icon-" + n + ".png").toFile());
            dibs.add(dib(img));
        }
        Files.write(out.resolve("mainframe.ico"), ico(SIZES, dibs));
        System.out.println("wrote " + out);
    }

    static BufferedImage render(int n) {
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.scale(n / 96.0, n / 96.0);

        Area body = new Area(round(19, 11, 58, 74, 7));
        Area cut = new Area();
        cut.add(new Area(circle(36, 33, 10)));
        cut.add(new Area(circle(60, 33, 10)));
        cut.subtract(new Area(circle(36, 33, 3)));
        cut.subtract(new Area(circle(60, 33, 3)));
        cut.add(new Area(round(29, 55, 38, 5, 2.5)));
        cut.add(new Area(round(29, 66, 38, 5, 2.5)));
        body.subtract(cut);

        g.setColor(HUE);
        g.fill(body);
        g.dispose();
        return img;
    }

    static Shape round(double x, double y, double w, double h, double r) {
        return new RoundRectangle2D.Double(x, y, w, h, r * 2, r * 2);
    }

    static Shape circle(double cx, double cy, double r) {
        return new Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2);
    }

    /** 32bpp bottom-up BGRA DIB plus an all-zero AND mask, as an .ico entry wants it. */
    static byte[] dib(BufferedImage img) throws IOException {
        int w = img.getWidth(), h = img.getHeight();
        int maskStride = ((w + 31) / 32) * 4;
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(b);
        le32(o, 40); le32(o, w); le32(o, h * 2);           // height counts XOR + AND
        le16(o, 1); le16(o, 32); le32(o, 0);               // planes, bpp, BI_RGB
        le32(o, w * h * 4 + maskStride * h);
        le32(o, 0); le32(o, 0); le32(o, 0); le32(o, 0);
        for (int y = h - 1; y >= 0; y--) {
            for (int x = 0; x < w; x++) {
                int argb = img.getRGB(x, y);
                o.write(argb & 0xff); o.write((argb >> 8) & 0xff);
                o.write((argb >> 16) & 0xff); o.write((argb >>> 24) & 0xff);
            }
        }
        o.write(new byte[maskStride * h]);
        return b.toByteArray();
    }

    static byte[] ico(int[] sizes, List<byte[]> dibs) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(b);
        le16(o, 0); le16(o, 1); le16(o, sizes.length);
        int offset = 6 + 16 * sizes.length;
        for (int i = 0; i < sizes.length; i++) {
            int n = sizes[i];
            o.write(n == 256 ? 0 : n); o.write(n == 256 ? 0 : n);
            o.write(0); o.write(0);
            le16(o, 1); le16(o, 32);
            le32(o, dibs.get(i).length); le32(o, offset);
            offset += dibs.get(i).length;
        }
        for (byte[] d : dibs) o.write(d);
        return b.toByteArray();
    }

    static void le16(DataOutputStream o, int v) throws IOException { o.write(v & 0xff); o.write((v >> 8) & 0xff); }
    static void le32(DataOutputStream o, int v) throws IOException { le16(o, v); le16(o, v >>> 16); }
}
