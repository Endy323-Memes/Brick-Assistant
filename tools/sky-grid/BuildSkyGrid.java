import javax.imageio.*;
import javax.imageio.stream.*;
import java.awt.Rectangle;
import java.awt.image.*;
import java.io.*;
import java.util.Iterator;
import java.util.zip.GZIPOutputStream;

/**
 * Shrinks the World Atlas of Artificial Night Sky Brightness (30" GeoTIFF of artificial zenith brightness in
 * mcd/m^2) to Brick's one-byte-per-cell sky grid.
 *
 * Usage: java BuildSkyGrid.java <atlas.tif> <out.bin.gz> <factor> <north> <west> <pixelDeg> [nodata]
 * Each output cell averages factor x factor pixels in linear brightness, adds the natural sky (0.171168 mcd/m^2,
 * i.e. 22.0 mag/arcsec^2) and stores SQM as round((22.0 - sqm) / 0.025), 0..254. Cells with no land data
 * (no-data value or NaN everywhere in the block) become 255.
 */
public class BuildSkyGrid {
    static final double NATURAL = 0.171168;

    public static void main(String[] a) throws Exception {
        File src = new File(a[0]);
        File out = new File(a[1]);
        int f = Integer.parseInt(a[2]);
        double north = Double.parseDouble(a[3]), west = Double.parseDouble(a[4]), px = Double.parseDouble(a[5]);
        Double nodata = a.length > 6 ? Double.valueOf(a[6]) : null;

        ImageInputStream in = ImageIO.createImageInputStream(src);
        Iterator<ImageReader> it = ImageIO.getImageReaders(in);
        ImageReader r = it.next();
        r.setInput(in, true, true);
        int W = r.getWidth(0), H = r.getHeight(0);
        int w = W / f, h = H / f;
        byte[] cells = new byte[w * h];
        int stripRows = f * 50;
        long sea = 0, land = 0;
        double[] sum = new double[w];
        int[] cnt = new int[w];
        for (int y0 = 0; y0 < h * f; y0 += stripRows) {
            int rows = Math.min(stripRows, h * f - y0);
            ImageReadParam p = r.getDefaultReadParam();
            p.setSourceRegion(new Rectangle(0, y0, w * f, rows));
            Raster ras = r.read(0, p).getRaster();
            float[] line = new float[w * f];
            for (int by = 0; by < rows / f; by++) {
                java.util.Arrays.fill(sum, 0); java.util.Arrays.fill(cnt, 0);
                for (int dy = 0; dy < f; dy++) {
                    ras.getSamples(ras.getMinX(), ras.getMinY() + by * f + dy, w * f, 1, 0, line);
                    for (int x = 0; x < w * f; x++) {
                        float v = line[x];
                        if (Float.isNaN(v) || (nodata != null && v == nodata) || v < 0) continue;
                        sum[x / f] += v; cnt[x / f]++;
                    }
                }
                int row = y0 / f + by;
                for (int c = 0; c < w; c++) {
                    int code;
                    if (cnt[c] == 0) { code = 255; sea++; }
                    else {
                        double art = sum[c] / cnt[c];
                        double sqm = Math.log10((art + NATURAL) / 108000000.0) / -0.4;
                        code = (int) Math.round((22.0 - sqm) / 0.025);
                        code = Math.max(0, Math.min(254, code));
                        land++;
                    }
                    cells[row * w + c] = (byte) code;
                }
            }
            System.out.printf("rows %d/%d%n", y0 + rows, h * f);
        }
        try (DataOutputStream o = new DataOutputStream(new GZIPOutputStream(new BufferedOutputStream(new FileOutputStream(out)), 1 << 16))) {
            o.writeBytes("BSKY"); o.writeInt(1); o.writeInt(w); o.writeInt(h);
            o.writeDouble(north); o.writeDouble(west); o.writeDouble(px * f);
            o.write(cells);
        }
        System.out.printf("grid %d x %d, land cells %d, no-data cells %d, file %d bytes%n", w, h, land, sea, out.length());
    }
}
