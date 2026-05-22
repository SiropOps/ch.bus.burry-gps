package ch.bus.gps.service;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ch.bus.gps.dto.GpsDTO;

@Service
public class GpsMapBmpService {

  private static final int INKPLATE_WIDTH = 1200;
  private static final int INKPLATE_HEIGHT = 825;
  private static final int TILE_SIZE = 256;
  private static final int MIN_ZOOM = 0;
  private static final int MAX_ZOOM = 19;
  private static final int MAP_WHITE_THRESHOLD = 245;
  private static final int MAP_LIGHT_GRAY_THRESHOLD = 220;
  private static final int MAP_MEDIUM_GRAY_THRESHOLD = 190;
  private static final int MAP_LABEL_THRESHOLD = 160;
  private static final int MAP_DARK_THRESHOLD = 120;
  private static final double BBOX_PADDING_RATIO = 0.15D;
  private static final double MIN_GEO_SPAN = 0.0001D;
  private static final double MIN_TILE_SPAN = 0.0000001D;
  private static final long MIN_VALID_TILE_FILE_SIZE_BYTES = 200L;
  private static final Logger LOGGER = LoggerFactory.getLogger(GpsMapBmpService.class);

  @Value("${gps.map.tile-url-template:https://tile.openstreetmap.org/{z}/{x}/{y}.png}")
  private String tileUrlTemplate;

  @Value("${gps.map.tile-user-agent:RoadPanel/1.0 (https://altidoma.ch; contact: info@altidoma.ch)}")
  private String tileUserAgent;

  @Value("${gps.map.tile-referer:https://altidoma.ch/}")
  private String tileReferer;

  @Value("${gps.map.tile-cache-dir:/tmp/osm-tile-cache}")
  private String tileCacheDir;

  @Value("${gps.map.tile-timeout-ms:10000}")
  private int tileTimeoutMs;

  public byte[] createBmpFromGpsPoints(List<GpsDTO> gpsPoints) throws IOException {
    BufferedImage bufferedImage =
        new BufferedImage(INKPLATE_WIDTH, INKPLATE_HEIGHT, BufferedImage.TYPE_INT_RGB);

    Graphics2D graphics = bufferedImage.createGraphics();

    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
    graphics.setColor(Color.WHITE);
    graphics.fillRect(0, 0, INKPLATE_WIDTH, INKPLATE_HEIGHT);

    List<Point2D.Double> geoPoints = this.extractGeoPoints(gpsPoints);

    if (!geoPoints.isEmpty()) {
      BBox bbox = this.computeExpandedBBox(geoPoints);
      int zoom = this.computeBestZoom(bbox, INKPLATE_WIDTH, INKPLATE_HEIGHT);
      MapViewport viewport = this.computeViewport(bbox, zoom);

      this.drawOsmTiles(graphics, viewport);
      this.simplifyMapForEpaper(bufferedImage);
      this.drawTrack(graphics, geoPoints, viewport);
    }

    graphics.dispose();

    BufferedImage binaryImage =
        new BufferedImage(INKPLATE_WIDTH, INKPLATE_HEIGHT, BufferedImage.TYPE_INT_RGB);

    Graphics2D binaryGraphics = binaryImage.createGraphics();
    binaryGraphics.drawImage(bufferedImage, 0, 0, null);
    binaryGraphics.dispose();

    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

    boolean ok = ImageIO.write(binaryImage, "bmp", outputStream);
    if (!ok) {
      throw new IOException("No ImageIO writer found for BMP format");
    }

    return outputStream.toByteArray();
  }

  private void simplifyMapForEpaper(BufferedImage image) {
    for (int y = 0; y < image.getHeight(); y++) {
      for (int x = 0; x < image.getWidth(); x++) {
        Color c = new Color(image.getRGB(x, y));

        int gray = (int) ((c.getRed() * 0.299) + (c.getGreen() * 0.587) + (c.getBlue() * 0.114));

        int out;
        if (gray > MAP_WHITE_THRESHOLD) {
          out = 255;
        } else if (gray > MAP_LIGHT_GRAY_THRESHOLD) {
          out = 245;
        } else if (gray > MAP_MEDIUM_GRAY_THRESHOLD) {
          out = 220;
        } else if (gray > MAP_LABEL_THRESHOLD) {
          out = 170;
        } else if (gray > MAP_DARK_THRESHOLD) {
          out = 90;
        } else {
          out = 0;
        }

        image.setRGB(x, y, new Color(out, out, out).getRGB());
      }
    }
  }

  private List<Point2D.Double> extractGeoPoints(List<GpsDTO> gpsPoints) {
    List<Point2D.Double> geoPoints = new ArrayList<>();

    for (GpsDTO point : gpsPoints) {
      if (point.getLatitude() == null || point.getLongitude() == null) {
        continue;
      }

      double lat = point.getLongitude();
      double lon = point.getLatitude();

      if (lat < -90 || lat > 90 || lon < -180 || lon > 180) {
        LOGGER.warn("Invalid GPS point ignored: lat={}, lon={}", lat, lon);
        continue;
      }

      geoPoints.add(new Point2D.Double(lon, lat));
    }

    return geoPoints;
  }

  private BBox computeExpandedBBox(List<Point2D.Double> geoPoints) {
    double minLon = geoPoints.stream().mapToDouble(Point2D.Double::getX).min().orElse(0D);
    double maxLon = geoPoints.stream().mapToDouble(Point2D.Double::getX).max().orElse(0D);
    double minLat = geoPoints.stream().mapToDouble(Point2D.Double::getY).min().orElse(0D);
    double maxLat = geoPoints.stream().mapToDouble(Point2D.Double::getY).max().orElse(0D);

    double lonSpan = Math.max(maxLon - minLon, MIN_GEO_SPAN);
    double latSpan = Math.max(maxLat - minLat, MIN_GEO_SPAN);

    double lonMargin = lonSpan * BBOX_PADDING_RATIO;
    double latMargin = latSpan * BBOX_PADDING_RATIO;

    BBox paddedBBox =
        new BBox(minLon - lonMargin, minLat - latMargin, maxLon + lonMargin, maxLat + latMargin);

    return this.expandBBoxToImageAspect(paddedBBox);
  }

  private BBox expandBBoxToImageAspect(BBox bbox) {
    double targetAspect = (double) INKPLATE_WIDTH / (double) INKPLATE_HEIGHT;
    double minX = lonToTileX(bbox.minLon, 0);
    double maxX = lonToTileX(bbox.maxLon, 0);
    double minY = latToTileY(bbox.maxLat, 0);
    double maxY = latToTileY(bbox.minLat, 0);

    double xSpan = Math.max(maxX - minX, MIN_TILE_SPAN);
    double ySpan = Math.max(maxY - minY, MIN_TILE_SPAN);
    double mapAspect = xSpan / ySpan;

    if (mapAspect < targetAspect) {
      double targetXSpan = ySpan * targetAspect;
      double xCenter = (minX + maxX) / 2.0D;
      minX = xCenter - (targetXSpan / 2.0D);
      maxX = xCenter + (targetXSpan / 2.0D);
    } else if (mapAspect > targetAspect) {
      double targetYSpan = xSpan / targetAspect;
      double yCenter = (minY + maxY) / 2.0D;
      minY = yCenter - (targetYSpan / 2.0D);
      maxY = yCenter + (targetYSpan / 2.0D);
    }

    minX = clamp(minX, 0.0D, 1.0D);
    maxX = clamp(maxX, 0.0D, 1.0D);
    minY = clamp(minY, 0.0D, 1.0D);
    maxY = clamp(maxY, 0.0D, 1.0D);

    return new BBox(tileXToLon(minX, 0), tileYToLat(maxY, 0), tileXToLon(maxX, 0),
        tileYToLat(minY, 0));
  }

  private int computeBestZoom(BBox bbox, int imageWidth, int imageHeight) {
    for (int zoom = MAX_ZOOM; zoom >= MIN_ZOOM; zoom--) {
      double minX = lonToTileX(bbox.minLon, zoom);
      double maxX = lonToTileX(bbox.maxLon, zoom);
      double minY = latToTileY(bbox.maxLat, zoom);
      double maxY = latToTileY(bbox.minLat, zoom);

      double pixelWidth = Math.abs(maxX - minX) * TILE_SIZE;
      double pixelHeight = Math.abs(maxY - minY) * TILE_SIZE;

      if (pixelWidth <= imageWidth && pixelHeight <= imageHeight) {
        return zoom;
      }
    }

    return MIN_ZOOM;
  }

  private MapViewport computeViewport(BBox bbox, int zoom) {
    double minTileX = lonToTileX(bbox.minLon, zoom);
    double maxTileX = lonToTileX(bbox.maxLon, zoom);
    double minTileY = latToTileY(bbox.maxLat, zoom);
    double maxTileY = latToTileY(bbox.minLat, zoom);

    double mapPixelWidth = (maxTileX - minTileX) * TILE_SIZE;
    double mapPixelHeight = (maxTileY - minTileY) * TILE_SIZE;

    double scaleX = INKPLATE_WIDTH / mapPixelWidth;
    double scaleY = INKPLATE_HEIGHT / mapPixelHeight;
    double scale = Math.min(scaleX, scaleY);

    double xOffset = (INKPLATE_WIDTH - (mapPixelWidth * scale)) / 2.0D;
    double yOffset = (INKPLATE_HEIGHT - (mapPixelHeight * scale)) / 2.0D;

    return new MapViewport(zoom, minTileX, maxTileX, minTileY, maxTileY, scale, xOffset, yOffset);
  }

  private void drawOsmTiles(Graphics2D graphics, MapViewport viewport) {
    int tileXStart = (int) Math.floor(viewport.minTileX);
    int tileXEnd = (int) Math.floor(viewport.maxTileX);
    int tileYStart = (int) Math.floor(viewport.minTileY);
    int tileYEnd = (int) Math.floor(viewport.maxTileY);

    for (int tileX = tileXStart; tileX <= tileXEnd; tileX++) {
      for (int tileY = tileYStart; tileY <= tileYEnd; tileY++) {
        BufferedImage tile = this.getTileOrNull(viewport.zoom, tileX, tileY);

        double drawX = viewport.toPixelX(tileX);
        double drawY = viewport.toPixelY(tileY);
        int drawSize = (int) Math.ceil(TILE_SIZE * viewport.scale);

        if (tile == null) {
          this.drawMissingTilePlaceholder(graphics, (int) Math.round(drawX),
              (int) Math.round(drawY), drawSize);
          continue;
        }

        graphics.drawImage(tile, (int) Math.round(drawX), (int) Math.round(drawY), drawSize,
            drawSize, null);
      }
    }
  }

  private void drawTrack(Graphics2D graphics, List<Point2D.Double> geoPoints,
      MapViewport viewport) {

    graphics.setColor(Color.BLACK);
    graphics.setStroke(new BasicStroke(7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

    List<Point2D.Double> scaledPoints = new ArrayList<>();

    for (Point2D.Double geoPoint : geoPoints) {
      double tileX = lonToTileX(geoPoint.getX(), viewport.zoom);
      double tileY = latToTileY(geoPoint.getY(), viewport.zoom);

      scaledPoints.add(new Point2D.Double(viewport.toPixelX(tileX), viewport.toPixelY(tileY)));
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

      graphics.fillOval(x - 3, y - 3, 7, 7);
    }
  }

  private BufferedImage getTileOrNull(int zoom, int x, int y) {

    int maxTileIndex = (1 << zoom) - 1;

    if (x < 0 || y < 0 || x > maxTileIndex || y > maxTileIndex) {
      return null;
    }

    Path tilePath = Path.of(this.tileCacheDir)
        .resolve(Path.of(Integer.toString(zoom), Integer.toString(x), y + ".png"));

    try {
      if (Files.exists(tilePath)) {
        BufferedImage cachedTile = this.readValidCachedTile(tilePath);
        if (cachedTile != null) {
          return cachedTile;
        }
      }

      Files.createDirectories(tilePath.getParent());
      BufferedImage downloadedTile = this.downloadTile(zoom, x, y);

      if (downloadedTile != null) {
        ImageIO.write(downloadedTile, "png", tilePath.toFile());
        LOGGER.info("Cached tile {}", tilePath);
      }

      return downloadedTile;
    } catch (IOException e) {
      LOGGER.warn("Tile load failure for z=" + zoom + ", x=" + x + ", y=" + y + " (cache="
          + tilePath + ")", e);
      return null;
    }
  }

  private BufferedImage readValidCachedTile(Path tilePath) throws IOException {
    long fileSize = Files.size(tilePath);
    if (fileSize < MIN_VALID_TILE_FILE_SIZE_BYTES) {
      LOGGER.warn("Deleting too-small cached tile: {} ({} bytes)", tilePath, fileSize);
      Files.deleteIfExists(tilePath);
      return null;
    }

    BufferedImage cachedTile = ImageIO.read(tilePath.toFile());
    if (cachedTile == null) {
      LOGGER.warn("Deleting invalid cached tile: {}", tilePath);
      Files.deleteIfExists(tilePath);
      return null;
    }

    return cachedTile;
  }

  private BufferedImage downloadTile(int zoom, int x, int y) throws IOException {
    String tileUrl = this.tileUrlTemplate.replace("{z}", Integer.toString(zoom))
        .replace("{x}", Integer.toString(x)).replace("{y}", Integer.toString(y));

    HttpURLConnection connection = (HttpURLConnection) new URL(tileUrl).openConnection();
    connection.setRequestMethod("GET");
    connection.setRequestProperty("User-Agent", this.tileUserAgent);
    connection.setRequestProperty("Referer", this.tileReferer);
    connection.setRequestProperty("Accept", "image/png,image/*,*/*");
    connection.setConnectTimeout(this.tileTimeoutMs);
    connection.setReadTimeout(this.tileTimeoutMs);

    int status = connection.getResponseCode();
    String contentType = connection.getContentType();

    LOGGER.info("Tile request url=" + tileUrl + ", status=" + status + ", contentType="
        + contentType + ", cacheDir=" + this.tileCacheDir);

    boolean imageContentType =
        contentType != null && (contentType.toLowerCase().contains("image/png")
            || contentType.toLowerCase().contains("image/jpeg")
            || contentType.toLowerCase().contains("image/jpg"));

    if (status != HttpURLConnection.HTTP_OK || !imageContentType) {
      this.logErrorBody(connection, tileUrl, status, contentType);
      return null;
    }

    try (InputStream inputStream = connection.getInputStream()) {
      BufferedImage tile = ImageIO.read(inputStream);
      if (tile == null) {
        LOGGER.warn("Tile decode failed for url={}", tileUrl);
      }
      return tile;
    } finally {
      connection.disconnect();
    }
  }

  private void logErrorBody(HttpURLConnection connection, String tileUrl, int status,
      String contentType) throws IOException {
    String responseBody = "";
    try (InputStream errorStream = connection.getErrorStream()) {
      if (errorStream != null) {
        responseBody = new String(errorStream.readAllBytes(), StandardCharsets.UTF_8);
        responseBody = responseBody.replaceAll("\\s+", " ").trim();
      }
    }

    if (responseBody.length() > 180) {
      responseBody = responseBody.substring(0, 180) + "...";
    }

    LOGGER.warn("Tile rejected url=" + tileUrl + ", status=" + status + ", contentType="
        + contentType + ", body=" + responseBody);
  }

  private void drawMissingTilePlaceholder(Graphics2D graphics, int x, int y, int size) {
    graphics.setColor(Color.WHITE);
    graphics.fillRect(x, y, size, size);
    graphics.setColor(new Color(210, 210, 210));
    graphics.drawRect(x, y, size, size);
  }

  private static double lonToTileX(double lon, int zoom) {
    return (lon + 180.0D) / 360.0D * (1 << zoom);
  }

  private static double tileXToLon(double tileX, int zoom) {
    return (tileX / (1 << zoom) * 360.0D) - 180.0D;
  }

  private static double latToTileY(double lat, int zoom) {

    double clampedLat = Math.max(-85.05112878D, Math.min(85.05112878D, lat));

    double latRad = Math.toRadians(clampedLat);

    return (1.0D - Math.log(Math.tan(latRad) + (1.0D / Math.cos(latRad))) / Math.PI) / 2.0D
        * (1 << zoom);
  }

  private static double tileYToLat(double tileY, int zoom) {
    double n = Math.PI - (2.0D * Math.PI * tileY) / (1 << zoom);

    return Math.toDegrees(Math.atan(Math.sinh(n)));
  }

  private static double clamp(double value, double min, double max) {
    return Math.max(min, Math.min(max, value));
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

  private static class MapViewport {

    private final int zoom;
    private final double minTileX;
    private final double maxTileX;
    private final double minTileY;
    private final double maxTileY;
    private final double scale;
    private final double xOffset;
    private final double yOffset;

    private MapViewport(int zoom, double minTileX, double maxTileX, double minTileY,
        double maxTileY, double scale, double xOffset, double yOffset) {

      this.zoom = zoom;
      this.minTileX = minTileX;
      this.maxTileX = maxTileX;
      this.minTileY = minTileY;
      this.maxTileY = maxTileY;
      this.scale = scale;
      this.xOffset = xOffset;
      this.yOffset = yOffset;
    }

    private double toPixelX(double tileX) {
      return this.xOffset + ((tileX - this.minTileX) * TILE_SIZE * this.scale);
    }

    private double toPixelY(double tileY) {
      return this.yOffset + ((tileY - this.minTileY) * TILE_SIZE * this.scale);
    }
  }
}
