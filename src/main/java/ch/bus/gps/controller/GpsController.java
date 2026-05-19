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
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
  private static final Logger LOGGER = LoggerFactory.getLogger(GpsController.class);
  private static final long MIN_VALID_TILE_FILE_SIZE_BYTES = 200L;

  @Autowired
  private GpsService gpsService;

  @Value("${gps.map.tile-user-agent:RoadPanel/1.0 (https://altidoma.ch; contact: info@altidoma.ch)}")
  private String tileUserAgent;

  @Value("${gps.map.tile-referer:https://altidoma.ch/}")
  private String tileReferer;

  @Value("${gps.map.tile-cache-dir:/tmp/map-tile-cache}")
  private String tileCacheDir;

  @Value("${gps.map.tile-timeout-ms:10000}")
  private int tileTimeoutMs;

  @Value("${gps.map.osm-url-template:https://tile.openstreetmap.org/{z}/{x}/{y}.png}")
  private String osmUrlTemplate;

  @Value("${gps.map.light-url-template:https://a.basemaps.cartocdn.com/light_all/{z}/{x}/{y}.png}")
  private String lightUrlTemplate;

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
    this.getMapLightBmp(response);
  }

  @GetMapping(value = "/map-osm.bmp")
  public void getMapOsmBmp(HttpServletResponse response) throws IOException {
    List<GpsDTO> gpsPoints = this.gpsService.getAll();
    byte[] image = this.createBmpFromGpsPoints(gpsPoints, TileProvider.OSM);
    this.writeBmpResponse(response, image, "gps-map-osm.bmp");
  }

  @GetMapping(value = "/map-light.bmp")
  public void getMapLightBmp(HttpServletResponse response) throws IOException {
    List<GpsDTO> gpsPoints = this.gpsService.getAll();
    byte[] image = this.createBmpFromGpsPoints(gpsPoints, TileProvider.CARTO_LIGHT);
    this.writeBmpResponse(response, image, "gps-map-light.bmp");
  }

  private void writeBmpResponse(HttpServletResponse response, byte[] image, String filename)
      throws IOException {
    response.setContentType("image/bmp");
    response.setHeader("Content-Disposition", "inline; filename=\"" + filename + "\"");
    response.setContentLength(image.length);
    response.getOutputStream().write(image);
    response.getOutputStream().flush();
  }

  private byte[] createBmpFromGpsPoints(List<GpsDTO> gpsPoints, TileProvider provider)
      throws IOException {
    BufferedImage rgbImage =
        new BufferedImage(INKPLATE_WIDTH, INKPLATE_HEIGHT, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = rgbImage.createGraphics();

    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
    graphics.setColor(Color.WHITE);
    graphics.fillRect(0, 0, INKPLATE_WIDTH, INKPLATE_HEIGHT);

    List<Point2D.Double> geoPoints = this.extractGeoPoints(gpsPoints);

    if (!geoPoints.isEmpty()) {
      BBox bbox = this.computeExpandedBBox(geoPoints);
      int zoom = this.computeBestZoom(bbox, INKPLATE_WIDTH, INKPLATE_HEIGHT, IMAGE_MARGIN);
      this.drawMapTiles(graphics, bbox, zoom, this.resolveProviderConfig(provider));
      this.simplifyMapForEpaper(rgbImage);
      this.drawTrack(graphics, geoPoints, bbox, zoom);
    }

    graphics.dispose();
    BufferedImage binaryImage = this.convertToBinaryImage(rgbImage);
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    ImageIO.write(binaryImage, "bmp", outputStream);
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

  private void drawMapTiles(Graphics2D graphics, BBox bbox, int zoom, TileProviderConfig provider) {
    double minTileX = lonToTileX(bbox.minLon, zoom);
    double maxTileX = lonToTileX(bbox.maxLon, zoom);
    double minTileY = latToTileY(bbox.maxLat, zoom);
    double maxTileY = latToTileY(bbox.minLat, zoom);

    double mapPixelWidth = (maxTileX - minTileX) * TILE_SIZE;
    double mapPixelHeight = (maxTileY - minTileY) * TILE_SIZE;

    double scaleX = (INKPLATE_WIDTH - (2.0D * IMAGE_MARGIN)) / mapPixelWidth;
    double scaleY = (INKPLATE_HEIGHT - (2.0D * IMAGE_MARGIN)) / mapPixelHeight;
    double scale = Math.min(scaleX, scaleY);

    double xOffset =
        IMAGE_MARGIN + ((INKPLATE_WIDTH - (2.0D * IMAGE_MARGIN)) - (mapPixelWidth * scale)) / 2.0D;
    double yOffset = IMAGE_MARGIN
        + ((INKPLATE_HEIGHT - (2.0D * IMAGE_MARGIN)) - (mapPixelHeight * scale)) / 2.0D;

    int tileXStart = (int) Math.floor(minTileX);
    int tileXEnd = (int) Math.floor(maxTileX);
    int tileYStart = (int) Math.floor(minTileY);
    int tileYEnd = (int) Math.floor(maxTileY);

    for (int tileX = tileXStart; tileX <= tileXEnd; tileX++) {
      for (int tileY = tileYStart; tileY <= tileYEnd; tileY++) {
        BufferedImage tile = this.getTileOrNull(provider, zoom, tileX, tileY);

        double drawX = xOffset + ((tileX - minTileX) * TILE_SIZE * scale);
        double drawY = yOffset + ((tileY - minTileY) * TILE_SIZE * scale);
        int drawSize = (int) Math.ceil(TILE_SIZE * scale);

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

  private void drawTrack(Graphics2D graphics, List<Point2D.Double> geoPoints, BBox bbox, int zoom) {
    graphics.setColor(Color.BLACK);
    graphics.setStroke(new BasicStroke(5f));

    double minTileX = lonToTileX(bbox.minLon, zoom);
    double maxTileX = lonToTileX(bbox.maxLon, zoom);
    double minTileY = latToTileY(bbox.maxLat, zoom);
    double maxTileY = latToTileY(bbox.minLat, zoom);

    double mapPixelWidth = (maxTileX - minTileX) * TILE_SIZE;
    double mapPixelHeight = (maxTileY - minTileY) * TILE_SIZE;

    double scaleX = (INKPLATE_WIDTH - (2.0D * IMAGE_MARGIN)) / mapPixelWidth;
    double scaleY = (INKPLATE_HEIGHT - (2.0D * IMAGE_MARGIN)) / mapPixelHeight;
    double scale = Math.min(scaleX, scaleY);

    double xOffset =
        IMAGE_MARGIN + ((INKPLATE_WIDTH - (2.0D * IMAGE_MARGIN)) - (mapPixelWidth * scale)) / 2.0D;

    double yOffset = IMAGE_MARGIN
        + ((INKPLATE_HEIGHT - (2.0D * IMAGE_MARGIN)) - (mapPixelHeight * scale)) / 2.0D;

    List<Point2D.Double> scaledPoints = new ArrayList<>();

    for (Point2D.Double geoPoint : geoPoints) {

      double tileX = lonToTileX(geoPoint.getX(), zoom);
      double tileY = latToTileY(geoPoint.getY(), zoom);

      double pixelX = xOffset + ((tileX - minTileX) * TILE_SIZE * scale);
      double pixelY = yOffset + ((tileY - minTileY) * TILE_SIZE * scale);

      scaledPoints.add(new Point2D.Double(pixelX, pixelY));
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

  private BufferedImage getTileOrNull(TileProviderConfig provider, int zoom, int x, int y) {
    int maxTileIndex = (1 << zoom) - 1;

    if (x < 0 || y < 0 || x > maxTileIndex || y > maxTileIndex) {
      return null;
    }

    Path tilePath = Path.of(this.tileCacheDir).resolve(provider.cacheSubDirectory)
        .resolve(Path.of(Integer.toString(zoom), Integer.toString(x), y + ".png"));

    try {
      if (Files.exists(tilePath)) {
        BufferedImage cachedTile = this.readValidCachedTile(tilePath);
        if (cachedTile != null) {
          return cachedTile;
        }
      }

      Files.createDirectories(tilePath.getParent());
      BufferedImage downloadedTile = this.downloadTile(provider, zoom, x, y);

      if (downloadedTile != null) {
        ImageIO.write(downloadedTile, "png", tilePath.toFile());
        LOGGER.info("Cached tile {}", tilePath);
      }

      return downloadedTile;
    } catch (IOException e) {
      LOGGER.warn("Tile load failure provider={}, z={}, x={}, y={} (cache={})", provider.name,
          zoom, x, y, tilePath, e);
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

  private BufferedImage downloadTile(TileProviderConfig provider, int zoom, int x, int y)
      throws IOException {
    String tileUrl = provider.urlTemplate.replace("{z}", Integer.toString(zoom))
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

    LOGGER.info("Tile request provider={}, url={}, status={}, contentType={}, cacheDir={}",
        provider.name, tileUrl, status, contentType, this.tileCacheDir);

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

    LOGGER.warn("Tile rejected url={}, status={}, contentType={}, body={}", tileUrl, status,
        contentType, responseBody);
  }

  private void drawMissingTilePlaceholder(Graphics2D graphics, int x, int y, int size) {
    graphics.setColor(Color.WHITE);
    graphics.fillRect(x, y, size, size);
    graphics.setColor(new Color(210, 210, 210));
    graphics.drawRect(x, y, size, size);
  }

  private void simplifyMapForEpaper(BufferedImage image) {
    for (int y = 0; y < image.getHeight(); y++) {
      for (int x = 0; x < image.getWidth(); x++) {
        int rgb = image.getRGB(x, y);
        int red = (rgb >> 16) & 0xFF;
        int green = (rgb >> 8) & 0xFF;
        int blue = rgb & 0xFF;

        int gray = (red + green + blue) / 3;
        gray = Math.min(255, gray + 60);

        int mappedGray;
        if (gray > 210) {
          mappedGray = 255;
        } else if (gray > 160) {
          mappedGray = 225;
        } else {
          mappedGray = 80;
        }

        int newRgb = (mappedGray << 16) | (mappedGray << 8) | mappedGray;
        image.setRGB(x, y, newRgb);
      }
    }
  }

  private BufferedImage convertToBinaryImage(BufferedImage source) {
    BufferedImage binaryImage =
        new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_BYTE_BINARY);
    Graphics2D graphics = binaryImage.createGraphics();
    graphics.drawImage(source, 0, 0, null);
    graphics.dispose();
    return binaryImage;
  }

  private TileProviderConfig resolveProviderConfig(TileProvider provider) {
    if (provider == TileProvider.CARTO_LIGHT) {
      return new TileProviderConfig("carto-light", this.lightUrlTemplate, "carto-light");
    }

    return new TileProviderConfig("osm", this.osmUrlTemplate, "osm");
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

  private enum TileProvider {
    OSM, CARTO_LIGHT
  }

  private static class TileProviderConfig {

    private final String name;
    private final String urlTemplate;
    private final String cacheSubDirectory;

    private TileProviderConfig(String name, String urlTemplate, String cacheSubDirectory) {
      this.name = name;
      this.urlTemplate = urlTemplate;
      this.cacheSubDirectory = cacheSubDirectory;
    }
  }
}
