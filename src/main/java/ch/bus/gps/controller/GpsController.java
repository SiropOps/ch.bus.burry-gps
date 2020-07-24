package ch.bus.gps.controller;

import java.util.Date;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ch.bus.gps.dto.GpsDTO;
import ch.bus.gps.service.GpsService;

@RestController
@RequestMapping("/api/gps")
public class GpsController {

  @Autowired
  private GpsService gpsService;

  @GetMapping("/test/{value}")
  public ResponseEntity<String> getTest(@PathVariable("value") String value) {
    return new ResponseEntity<>(value + ":" + new Date().toString(), HttpStatus.OK);
  }

  @GetMapping("/")
  public ResponseEntity<GpsDTO> get() {
    return new ResponseEntity<>(this.gpsService.getLast(), HttpStatus.OK);
  }

  @DeleteMapping("/")
  public ResponseEntity<Void> Stop() {
    this.gpsService.stop();
    return new ResponseEntity<>(HttpStatus.OK);
  }

  @GetMapping("/all/")
  public ResponseEntity<List<GpsDTO>> getAll() {
    return new ResponseEntity<>(this.gpsService.getAll(), HttpStatus.OK);
  }
}
