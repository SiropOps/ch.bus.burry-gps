package ch.bus.gps.controller;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ch.bus.gps.dto.GpsDTO;
import ch.bus.gps.dto.SpeakingClockDTO;
import ch.bus.gps.service.GpsService;

@RestController
@RequestMapping("/api/gps")
public class GpsController {

  private static final int INKPLATE_WIDTH = 1200;
  private static final int INKPLATE_HEIGHT = 825;
  private static final int IMAGE_MARGIN = 20;
  private static final int TILE_SIZE = 256;
  private static final int MIN_ZOOM = 0;
  private static final int MAX_ZOOM = 19;
  private static final String OSM_TILE_URL_TEMPLATE = "https://tile.openstreetmap.org/%d/%d/%d.png";
  private static final Path OSM_TILE_CACHE_DIR =
      Path.of(System.getProperty("java.io.tmpdir"), "osm-tile-cache");

  @Autowired
  private GpsService gpsService;

  @GetMapping("/speaking_clock")
  public ResponseEntity<SpeakingClockDTO> getSpeakingClock() {
    return new ResponseEntity<>(this.gpsService.getSpeakingClock(), HttpStatus.OK);
  }

  @GetMapping("")
  public ResponseEntity<GpsDTO> get() {
    return new ResponseEntity<>(this.gpsService.getLast(), HttpStatus.OK);
  }

  @DeleteMapping("")
  public ResponseEntity<Void> Stop() {
    this.gpsService.stop();
    return new ResponseEntity<>(HttpStatus.OK);
  }

  @GetMapping("/all")
  public ResponseEntity<List<GpsDTO>> getAll() {
    return new ResponseEntity<>(this.gpsService.getAll(), HttpStatus.OK);
  }

  @GetMapping(value = "/map.bmp")
  public void getMapBmp(HttpServletResponse response) throws IOException {
    List<GpsDTO> gpsPoints = this.gpsService.getAll();
    byte[] image = this.createBmpFromGpsPoints(gpsPoints);

    response.setContentType("image/bmp");
    response.setHeader("Content-Disposition", "inline; filename=\"gps-map.bmp\"");
    response.setContentLength(image.length);

    response.getOutputStream().write(image);
    response.getOutputStream().flush();
  }

  private byte[] createBmpFromGpsPoints(List<GpsDTO> gpsPoints) throws IOException {
    BufferedImage bufferedImage =
        new BufferedImage(INKPLATE_WIDTH, INKPLATE_HEIGHT, BufferedImage.TYPE_BYTE_BINARY);
    Graphics2D graphics = bufferedImage.createGraphics();

    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
    graphics.setColor(Color.WHITE);
    graphics.fillRect(0, 0, INKPLATE_WIDTH, INKPLATE_HEIGHT);

    List<Point2D.Double> geoPoints = this.extractGeoPoints(gpsPoints);

    if (!geoPoints.isEmpty()) {
      BBox bbox = this.computeExpandedBBox(geoPoints);
      int zoom = this.computeBestZoom(bbox, INKPLATE_WIDTH, INKPLATE_HEIGHT, IMAGE_MARGIN);
      this.drawOsmTiles(graphics, bbox, zoom);
      this.drawTrack(graphics, geoPoints, bbox);
    }

    graphics.dispose();
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    ImageIO.write(bufferedImage, "bmp", outputStream);
    return outputStream.toByteArray();
  }

  private List<Point2D.Double> extractGeoPoints(List<GpsDTO> gpsPoints) {
    List<Point2D.Double> geoPoints = new ArrayList<>();
    for (GpsDTO point : gpsPoints) {
      if (point.getLatitude() != null && point.getLongitude() != null) {
        geoPoints.add(new Point2D.Double(point.getLongitude(), point.getLatitude()));
      }
    }
    return geoPoints;
  }

  private BBox computeExpandedBBox(List<Point2D.Double> geoPoints) {
    double minLon = geoPoints.stream().mapToDouble(Point2D.Double::getX).min().orElse(0D);
    double maxLon = geoPoints.stream().mapToDouble(Point2D.Double::getX).max().orElse(0D);
    double minLat = geoPoints.stream().mapToDouble(Point2D.Double::getY).min().orElse(0D);
    double maxLat = geoPoints.stream().mapToDouble(Point2D.Double::getY).max().orElse(0D);

    double lonSpan = Math.max(maxLon - minLon, 0.0001D);
    double latSpan = Math.max(maxLat - minLat, 0.0001D);

    double lonMargin = lonSpan * 0.15D;
    double latMargin = latSpan * 0.15D;

    return new BBox(minLon - lonMargin, minLat - latMargin, maxLon + lonMargin, maxLat + latMargin);
  }

  private int computeBestZoom(BBox bbox, int imageWidth, int imageHeight, int margin) {
    int drawableWidth = imageWidth - (2 * margin);
    int drawableHeight = imageHeight - (2 * margin);

    for (int zoom = MAX_ZOOM; zoom >= MIN_ZOOM; zoom--) {
      double minX = lonToTileX(bbox.minLon, zoom);
      double maxX = lonToTileX(bbox.maxLon, zoom);
      double minY = latToTileY(bbox.maxLat, zoom);
      double maxY = latToTileY(bbox.minLat, zoom);

      double pixelWidth = Math.abs(maxX - minX) * TILE_SIZE;
      double pixelHeight = Math.abs(maxY - minY) * TILE_SIZE;

      if (pixelWidth <= drawableWidth && pixelHeight <= drawableHeight) {
        return zoom;
      }
    }

    return MIN_ZOOM;
  }

  private void drawOsmTiles(Graphics2D graphics, BBox bbox, int zoom) throws IOException {
    double minTileX = lonToTileX(bbox.minLon, zoom);
    double maxTileX = lonToTileX(bbox.maxLon, zoom);
    double minTileY = latToTileY(bbox.maxLat, zoom);
    double maxTileY = latToTileY(bbox.minLat, zoom);

    double mapPixelWidth = (maxTileX - minTileX) * TILE_SIZE;
    double mapPixelHeight = (maxTileY - minTileY) * TILE_SIZE;

    double scaleX = (INKPLATE_WIDTH - (2.0D * IMAGE_MARGIN)) / mapPixelWidth;
    double scaleY = (INKPLATE_HEIGHT - (2.0D * IMAGE_MARGIN)) / mapPixelHeight;
    double scale = Math.min(scaleX, scaleY);

    double xOffset = IMAGE_MARGIN + ((INKPLATE_WIDTH - (2.0D * IMAGE_MARGIN)) - (mapPixelWidth * scale)) / 2.0D;
    double yOffset = IMAGE_MARGIN + ((INKPLATE_HEIGHT - (2.0D * IMAGE_MARGIN)) - (mapPixelHeight * scale)) / 2.0D;

    int tileXStart = (int) Math.floor(minTileX);
    int tileXEnd = (int) Math.floor(maxTileX);
    int tileYStart = (int) Math.floor(minTileY);
    int tileYEnd = (int) Math.floor(maxTileY);

    for (int tileX = tileXStart; tileX <= tileXEnd; tileX++) {
      for (int tileY = tileYStart; tileY <= tileYEnd; tileY++) {
        BufferedImage tile = this.getTile(zoom, tileX, tileY);
        if (tile == null) {
          continue;
        }

        double drawX = xOffset + ((tileX - minTileX) * TILE_SIZE * scale);
        double drawY = yOffset + ((tileY - minTileY) * TILE_SIZE * scale);
        int drawSize = (int) Math.ceil(TILE_SIZE * scale);

        graphics.drawImage(tile, (int) Math.round(drawX), (int) Math.round(drawY), drawSize, drawSize,
            null);
      }
    }
  }

  private void drawTrack(Graphics2D graphics, List<Point2D.Double> geoPoints, BBox bbox) {
    graphics.setColor(Color.BLACK);
    graphics.setStroke(new BasicStroke(2f));

    double drawWidth = INKPLATE_WIDTH - (2.0D * IMAGE_MARGIN);
    double drawHeight = INKPLATE_HEIGHT - (2.0D * IMAGE_MARGIN);
    double lonRange = Math.max(bbox.maxLon - bbox.minLon, 0.000001D);
    double latRange = Math.max(bbox.maxLat - bbox.minLat, 0.000001D);

    List<Point2D.Double> scaledPoints = new ArrayList<>();
    for (Point2D.Double geoPoint : geoPoints) {
      double x = IMAGE_MARGIN + ((geoPoint.getX() - bbox.minLon) / lonRange) * drawWidth;
      double y = IMAGE_MARGIN + ((bbox.maxLat - geoPoint.getY()) / latRange) * drawHeight;
      scaledPoints.add(new Point2D.Double(x, y));
    }

    for (int i = 1; i < scaledPoints.size(); i++) {
      Point2D.Double p1 = scaledPoints.get(i - 1);
      Point2D.Double p2 = scaledPoints.get(i);
      graphics.drawLine((int) Math.round(p1.getX()), (int) Math.round(p1.getY()),
          (int) Math.round(p2.getX()), (int) Math.round(p2.getY()));
    }

    for (Point2D.Double point : scaledPoints) {
      int x = (int) Math.round(point.getX());
      int y = (int) Math.round(point.getY());
      graphics.fillRect(x - 1, y - 1, 3, 3);
    }
  }

  private BufferedImage getTile(int zoom, int x, int y) throws IOException {
    int maxTileIndex = (1 << zoom) - 1;
    if (x < 0 || y < 0 || x > maxTileIndex || y > maxTileIndex) {
      return null;
    }

    Path tilePath = OSM_TILE_CACHE_DIR.resolve(Path.of(Integer.toString(zoom), Integer.toString(x), y + ".png"));

    if (!Files.exists(tilePath)) {
      Files.createDirectories(tilePath.getParent());
      URL url = new URL(String.format(OSM_TILE_URL_TEMPLATE, zoom, x, y));
      try (InputStream inputStream = url.openStream()) {
        Files.copy(inputStream, tilePath, StandardCopyOption.REPLACE_EXISTING);
      }
    }

    return ImageIO.read(tilePath.toFile());
  }

  private static double lonToTileX(double lon, int zoom) {
    return (lon + 180.0D) / 360.0D * (1 << zoom);
  }

  private static double latToTileY(double lat, int zoom) {
    double clampedLat = Math.max(-85.05112878D, Math.min(85.05112878D, lat));
    double latRad = Math.toRadians(clampedLat);
    return (1.0D - Math.log(Math.tan(latRad) + (1.0D / Math.cos(latRad))) / Math.PI) / 2.0D
        * (1 << zoom);
  }

  private static class BBox {
    private final double minLon;
    private final double minLat;
    private final double maxLon;
    private final double maxLat;

    private BBox(double minLon, double minLat, double maxLon, double maxLat) {
      this.minLon = minLon;
      this.minLat = minLat;
      this.maxLon = maxLon;
      this.maxLat = maxLat;
    }
  }
}
