import java.awt.BasicStroke;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Marks the sea in Brick's sky grid. The World Atlas models the ocean as perfectly dark, the same as remote land,
 * so without this a "darkest sky" search can pick a spot offshore. Every cell that no land polygon touches becomes
 * 255 (sea). Coastal cells count as land, and so do small islands.
 *
 * Usage: java MaskSea.java <sky_grid.bin.gz> <ne_10m_land.shp> <out.bin.gz>
 * Land polygons: Natural Earth 1:10m Land (public domain), https://www.naturalearthdata.com/
 */
public class MaskSea {
    public static void main(String[] a) throws Exception {
        DataInputStream d = new DataInputStream(new GZIPInputStream(new BufferedInputStream(new FileInputStream(a[0]), 1 << 16)));
        byte[] magic = new byte[4];
        d.readFully(magic);
        int version = d.readInt(), w = d.readInt(), h = d.readInt();
        double north = d.readDouble(), west = d.readDouble(), cell = d.readDouble();
        byte[] cells = new byte[w * h];
        d.readFully(cells);
        d.close();

        // Rasterize the land: one pixel per grid cell.
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setColor(java.awt.Color.WHITE);
        AffineTransform toGrid = new AffineTransform(1 / cell, 0, 0, -1 / cell, -west / cell, north / cell);
        g.setStroke(new BasicStroke(0f));

        ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(new File(a[1]).toPath()));
        int pos = 100, shapes = 0;
        while (pos + 8 <= b.limit()) {
            b.order(ByteOrder.BIG_ENDIAN);
            int len = b.getInt(pos + 4) * 2;
            int rec = pos + 8;
            b.order(ByteOrder.LITTLE_ENDIAN);
            int type = b.getInt(rec);
            if (type == 5) {
                int parts = b.getInt(rec + 36), points = b.getInt(rec + 40);
                int partsAt = rec + 44, pointsAt = partsAt + 4 * parts;
                Path2D.Double path = new Path2D.Double(Path2D.WIND_EVEN_ODD);
                for (int p = 0; p < parts; p++) {
                    int start = b.getInt(partsAt + 4 * p);
                    int end = p + 1 < parts ? b.getInt(partsAt + 4 * (p + 1)) : points;
                    for (int i = start; i < end; i++) {
                        double x = b.getDouble(pointsAt + 16 * i), y = b.getDouble(pointsAt + 16 * i + 8);
                        if (i == start) path.moveTo(x, y); else path.lineTo(x, y);
                    }
                    path.closePath();
                }
                java.awt.Shape s = toGrid.createTransformedShape(path);
                g.fill(s);
                g.draw(s); // coasts and islands smaller than a cell still count as land
                shapes++;
            }
            pos = rec + len;
        }
        g.dispose();

        long land = 0, sea = 0;
        byte[] px = ((java.awt.image.DataBufferByte) img.getRaster().getDataBuffer()).getData();
        for (int i = 0; i < cells.length; i++) {
            if (px[i] == 0) { cells[i] = (byte) 255; sea++; } else land++;
        }
        try (DataOutputStream o = new DataOutputStream(new GZIPOutputStream(new BufferedOutputStream(new FileOutputStream(a[2])), 1 << 16))) {
            o.write(magic); o.writeInt(version); o.writeInt(w); o.writeInt(h);
            o.writeDouble(north); o.writeDouble(west); o.writeDouble(cell);
            o.write(cells);
        }
        System.out.printf("%d land shapes, %d land cells, %d sea cells, file %d bytes%n", shapes, land, sea, new File(a[2]).length());
    }
}
