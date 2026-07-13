package ch.bus.gps.service;

import static org.junit.Assert.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import ch.bus.gps.component.GpsComponent;
import ch.bus.gps.dto.GpsDTO;
import ch.bus.gps.dto.GpsStatusDTO;
import ch.bus.gps.entity.GpsPointFilteredByMinute;
import ch.bus.gps.repository.GpsPointFilteredByMinuteRepository;
import ch.bus.gps.repository.PgpsRepository;

@ExtendWith(MockitoExtension.class)
class GpsServiceTest {

  @Mock
  private PgpsRepository pgpsRepository;

  @Mock
  private GpsComponent gpsComponent;

  @Mock
  private GpsPointFilteredByMinuteRepository gpsPointFilteredByMinuteRepository;

  @InjectMocks
  private GpsService gpsService;

  @BeforeEach
  void resetCache() {
    gpsService.getAll().clear();
    getStaticMap("GPS_LAST_SIGNAL_BY_TYPE").clear();
    Map<String, Boolean> gpsRunningByType = getStaticMap("GPS_RUNNING_BY_TYPE");
    gpsRunningByType.clear();
    gpsRunningByType.put("USB", false);
    gpsRunningByType.put("ESP32", false);
  }

  @SuppressWarnings("unchecked")
  private <T> Map<String, T> getStaticMap(String fieldName) {
    return (Map<String, T>) ReflectionTestUtils.getField(GpsService.class, fieldName);
  }

  @Test
  void getAllInCacheShouldLoadDtoFromMaterializedViewRepository() {
    Point point = new GeometryFactory().createPoint(new Coordinate(6.14, 46.2));
    GpsPointFilteredByMinute row = new GpsPointFilteredByMinute();
    Date minute = new Date(1716111300000L);
    row.setMinute(minute);
    row.setAvgSpeed(11.2);
    row.setAvgSpeedError(0.4);
    row.setCoordinateAvgGeom(point);

    when(gpsPointFilteredByMinuteRepository.findAllByOrderByMinuteAsc())
        .thenReturn(Arrays.asList(row));

    gpsService.getAllInCache();
    InOrder inOrder = inOrder(gpsPointFilteredByMinuteRepository);
    inOrder.verify(gpsPointFilteredByMinuteRepository, times(1)).refreshMaterializedView();
    inOrder.verify(gpsPointFilteredByMinuteRepository, times(1)).findAllByOrderByMinuteAsc();

    List<GpsDTO> all = gpsService.getAll();
    assertEquals(1, all.size());
    assertNull(all.get(0).getTime());
    assertNull(all.get(0).getSpeed());
    assertNull(all.get(0).getEps());
    assertEquals(6.14, all.get(0).getLatitude());
    assertEquals(46.2, all.get(0).getLongitude());
    verify(gpsPointFilteredByMinuteRepository, times(1)).findAllByOrderByMinuteAsc();
  }

  @Test
  void getAllShouldReuseCacheWithoutReloadingRepository() {
    when(gpsPointFilteredByMinuteRepository.findAllByOrderByMinuteAsc())
        .thenReturn(Arrays.asList());

    gpsService.getAllInCache();
    gpsService.getAll();
    gpsService.getAll();

    verify(gpsPointFilteredByMinuteRepository, times(1)).refreshMaterializedView();
    verify(gpsPointFilteredByMinuteRepository, times(1)).findAllByOrderByMinuteAsc();
    assertTrue(gpsService.getAll().isEmpty());
  }

  @Test
  void receiveMessageShouldMarkGpsTypeAsRunning() {
    GpsDTO message = new GpsDTO();
    message.setGpsType("ESP32");
    message.setTime(new Date());
    message.setLatitude(46.2);
    message.setLongitude(6.14);
    message.setSpeed(0.0);

    when(pgpsRepository.createPoint(6.14, 46.2))
        .thenReturn(new GeometryFactory().createPoint(new Coordinate(6.14, 46.2)));

    gpsService.receiveMessage(message);

    GpsStatusDTO esp32Status = gpsService.getStatus().stream()
        .filter(status -> "ESP32".equals(status.getGpsType()))
        .findFirst()
        .orElseThrow();

    assertTrue(esp32Status.isRunning());
  }

  @Test
  void getStatusShouldMarkGpsAsStoppedAfterTimeout() {
    getStaticMap("GPS_LAST_SIGNAL_BY_TYPE").put("USB", new Date(System.currentTimeMillis() - 4000));
    getStaticMap("GPS_RUNNING_BY_TYPE").put("USB", true);

    GpsStatusDTO usbStatus = gpsService.getStatus().stream()
        .filter(status -> "USB".equals(status.getGpsType()))
        .findFirst()
        .orElseThrow();

    assertFalse(usbStatus.isRunning());
  }
}
