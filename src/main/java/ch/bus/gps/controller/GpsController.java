package ch.bus.gps.controller;

import java.io.IOException;
import java.util.List;
import javax.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ch.bus.gps.dto.GpsDTO;
import ch.bus.gps.dto.GpsStatusDTO;
import ch.bus.gps.dto.SpeakingClockDTO;
import ch.bus.gps.service.GpsMapBmpService;
import ch.bus.gps.service.GpsService;

@RestController
@RequestMapping("/api/gps")
public class GpsController {

  @Autowired
  private GpsService gpsService;

  @Autowired
  private GpsMapBmpService gpsMapBmpService;

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

  @GetMapping("/status")
  public ResponseEntity<List<GpsStatusDTO>> getStatus() {
    return new ResponseEntity<>(this.gpsService.getStatus(), HttpStatus.OK);
  }

  @GetMapping(value = "/map.bmp")
  public void getMapBmp(HttpServletResponse response) throws IOException {
    byte[] image = this.gpsMapBmpService.createBmpFromGpsPoints(this.gpsService.getAll());

    response.setContentType("image/bmp");
    response.setHeader("Content-Disposition", "inline; filename=\"gps-map.bmp\"");
    response.setContentLength(image.length);

    response.getOutputStream().write(image);
    response.getOutputStream().flush();
  }
}
