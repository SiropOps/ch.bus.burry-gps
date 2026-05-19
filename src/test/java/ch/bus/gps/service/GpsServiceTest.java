package ch.bus.gps.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
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
import ch.bus.gps.component.GpsComponent;
import ch.bus.gps.dto.GpsDTO;
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
    assertEquals(minute, all.get(0).getTime());
    assertEquals(11.2, all.get(0).getSpeed());
    assertEquals(0.4, all.get(0).getEps());
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
}
