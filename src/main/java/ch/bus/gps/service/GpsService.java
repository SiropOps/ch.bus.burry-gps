package ch.bus.gps.service;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ch.bus.gps.component.GpsComponent;
import ch.bus.gps.dto.GpsDTO;
import ch.bus.gps.dto.GpsStatusDTO;
import ch.bus.gps.dto.SpeakingClockDTO;
import ch.bus.gps.entity.GpsPointFilteredByMinute;
import ch.bus.gps.entity.Pgps;
import ch.bus.gps.repository.GpsPointFilteredByMinuteRepository;
import ch.bus.gps.repository.PgpsRepository;

@Service
public class GpsService {

  private static Logger log = LoggerFactory.getLogger(GpsService.class);

  @Autowired
  private PgpsRepository pgpsRepository;

  @Autowired
  private GpsComponent gpsComponent;

  @Autowired
  private GpsPointFilteredByMinuteRepository gpsPointFilteredByMinuteRepository;

  private static List<GpsDTO> CACHED_TRIPE = new ArrayList<>();
  private static boolean RUNNING = true;
  private static Pgps LAST = null;
  private static final String GPS_TYPE_USB = "USB";
  private static final String GPS_TYPE_ESP32 = "ESP32";
  private static final long GPS_SIGNAL_TIMEOUT_MS = 3000;
  private static final Map<String, Date> GPS_LAST_SIGNAL_BY_TYPE = new ConcurrentHashMap<>();
  private static final Map<String, Boolean> GPS_RUNNING_BY_TYPE = new ConcurrentHashMap<>();

  static {
    GPS_RUNNING_BY_TYPE.put(GPS_TYPE_USB, false);
    GPS_RUNNING_BY_TYPE.put(GPS_TYPE_ESP32, false);
  }

  private Date speakingClockDate = null;

  private boolean isNull(Double value) {
    return (value == null ? true : Double.isNaN(value));
  }

  @Transactional
  @RabbitListener(queues = "gps")
  public void receiveMessage(final GpsDTO gpsMessage) {
    if (gpsMessage == null || !RUNNING)
      return;

    this.updateGpsSignal(gpsMessage);

    if (Optional.ofNullable(gpsMessage.getTime()).isPresent()) {
      speakingClockDate = gpsMessage.getTime();
    }

    if (!Optional.ofNullable(gpsMessage.getTime()).isPresent() || isNull(gpsMessage.getLatitude())
        || isNull(gpsMessage.getLongitude()) || isNull(gpsMessage.getSpeed()))
      return;

    log.debug("Received message as specific class: {}", gpsMessage.toString());


    Pgps pgps = new Pgps();
    BeanUtils.copyProperties(gpsMessage, pgps);
    pgps.setAltitudeError(gpsMessage.getEpv());
    pgps.setClimbError(gpsMessage.getEpc());
    pgps.setLatitudeError(gpsMessage.getEpy());
    pgps.setLongitudeError(gpsMessage.getEpx());
    pgps.setSpeedError(gpsMessage.getEps());
    pgps.setTrackError(gpsMessage.getEpd());
    pgps.setCoordinate(
        this.pgpsRepository.createPoint(gpsMessage.getLongitude(), gpsMessage.getLatitude()));

    if (!Optional.ofNullable(LAST).isPresent() || pgps.getSpeed() > 0.5) {
      this.gpsComponent.addOrSave(pgps);
    }

    LAST = pgps;

  }

  private void updateGpsSignal(GpsDTO gpsMessage) {
    if (!Optional.ofNullable(gpsMessage.getGpsType()).isPresent())
      return;

    GPS_LAST_SIGNAL_BY_TYPE.put(gpsMessage.getGpsType(), new Date());
    GPS_RUNNING_BY_TYPE.put(gpsMessage.getGpsType(), true);
  }

  @Async
  @Scheduled(cron = "*/1 * * * * *")
  // each second.
  public synchronized void manageReceiveMessage() {
    log.debug("manageReceiveMessage");

    this.gpsComponent.addOrSave(null);
    this.updateGpsStatus();

  }

  private void updateGpsStatus() {
    Date now = new Date();
    GPS_RUNNING_BY_TYPE.forEach((gpsType, running) -> {
      Date lastSignalDate = GPS_LAST_SIGNAL_BY_TYPE.get(gpsType);
      GPS_RUNNING_BY_TYPE.put(gpsType,
          Optional.ofNullable(lastSignalDate).isPresent()
              && now.getTime() - lastSignalDate.getTime() <= GPS_SIGNAL_TIMEOUT_MS);
    });
  }

  public void stop() {
    RUNNING = false;
  }

  public GpsDTO getLast() {
    Pgps pgps = LAST;
    // if (!Optional.ofNullable(pgps).isPresent())
    pgps = this.pgpsRepository.getLast();
    GpsDTO gpsDTO = new GpsDTO();
    if (pgps == null)
      return gpsDTO;
    BeanUtils.copyProperties(pgps, gpsDTO);
    gpsDTO.setEpv(pgps.getAltitudeError());
    gpsDTO.setEpc(pgps.getClimbError());
    gpsDTO.setEpy(pgps.getLatitudeError());
    gpsDTO.setEpx(pgps.getLongitudeError());
    gpsDTO.setEps(pgps.getSpeedError());
    gpsDTO.setEpd(pgps.getTrackError());
    gpsDTO.setLatitude(pgps.getCoordinate().getX());
    gpsDTO.setLongitude(pgps.getCoordinate().getY());

    return gpsDTO;
  }

  @Async
  @Scheduled(cron = "0 0 */1 * * *")
  // At every hour.
  public void getAllInCache() {

    this.gpsPointFilteredByMinuteRepository.refreshMaterializedView();

    List<GpsPointFilteredByMinute> r =
        this.gpsPointFilteredByMinuteRepository.findAllByOrderByMinuteAsc();

    List<GpsDTO> list = new ArrayList<>();
    GpsDTO gpsDTO;

    Date limitDate = new GregorianCalendar(2020, Calendar.JANUARY, 1).getTime();
    for (GpsPointFilteredByMinute point : r) {
      if (Optional.ofNullable(point.getMinute()).orElse(new Date(1577836000000L))
          .after(limitDate)) {
        gpsDTO = new GpsDTO();
        gpsDTO.setLatitude(point.getCoordinateAvgGeom().getX());
        gpsDTO.setLongitude(point.getCoordinateAvgGeom().getY());
        list.add(gpsDTO);
      }
    }

    CACHED_TRIPE = list;
  }

  public List<GpsDTO> getAll() {
    return CACHED_TRIPE;
  }

  public List<GpsStatusDTO> getStatus() {
    this.updateGpsStatus();

    List<GpsStatusDTO> statuses = new ArrayList<>();
    GPS_RUNNING_BY_TYPE.forEach((gpsType, running) -> statuses
        .add(new GpsStatusDTO(gpsType, running, GPS_LAST_SIGNAL_BY_TYPE.get(gpsType))));
    statuses.sort(Comparator.comparing(GpsStatusDTO::getGpsType));
    return statuses;
  }

  public SpeakingClockDTO getSpeakingClock() {

    if (Optional.ofNullable(speakingClockDate).isPresent()
        && new Date(1628577925000l).before(speakingClockDate))
      // Tue Aug 10 08:45:25 CEST 2021
      return new SpeakingClockDTO(speakingClockDate);
    else
      return new SpeakingClockDTO(new Date());
  }

}
