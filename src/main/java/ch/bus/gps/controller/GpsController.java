package ch.bus.gps.controller;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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

    this.drawGrid(graphics);

    List<Point2D.Double> geoPoints = new ArrayList<>();
    for (GpsDTO point : gpsPoints) {
      if (point.getLatitude() != null && point.getLongitude() != null) {
        geoPoints.add(new Point2D.Double(point.getLongitude(), point.getLatitude()));
      }
    }

    if (!geoPoints.isEmpty()) {
      double minX = geoPoints.stream().mapToDouble(Point2D.Double::getX).min().orElse(0D);
      double maxX = geoPoints.stream().mapToDouble(Point2D.Double::getX).max().orElse(0D);
      double minY = geoPoints.stream().mapToDouble(Point2D.Double::getY).min().orElse(0D);
      double maxY = geoPoints.stream().mapToDouble(Point2D.Double::getY).max().orElse(0D);

      double rangeX = Math.max(maxX - minX, 0.000001D);
      double rangeY = Math.max(maxY - minY, 0.000001D);
      double drawWidth = INKPLATE_WIDTH - (2.0D * IMAGE_MARGIN);
      double drawHeight = INKPLATE_HEIGHT - (2.0D * IMAGE_MARGIN);

      List<Point2D.Double> scaledPoints = new ArrayList<>();
      for (Point2D.Double geoPoint : geoPoints) {
        double x = IMAGE_MARGIN + ((geoPoint.getX() - minX) / rangeX) * drawWidth;
        double y = IMAGE_MARGIN + ((maxY - geoPoint.getY()) / rangeY) * drawHeight;
        scaledPoints.add(new Point2D.Double(x, y));
      }

      graphics.setColor(Color.BLACK);
      graphics.setStroke(new BasicStroke(2f));

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

    graphics.dispose();
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    ImageIO.write(bufferedImage, "bmp", outputStream);
    return outputStream.toByteArray();
  }

  private void drawGrid(Graphics2D graphics) {
    graphics.setColor(Color.LIGHT_GRAY);
    graphics.setStroke(new BasicStroke(1f));

    int gridStep = 100;
    for (int x = 0; x <= INKPLATE_WIDTH; x += gridStep) {
      graphics.drawLine(x, 0, x, INKPLATE_HEIGHT);
    }

    for (int y = 0; y <= INKPLATE_HEIGHT; y += gridStep) {
      graphics.drawLine(0, y, INKPLATE_WIDTH, y);
    }
  }
}
