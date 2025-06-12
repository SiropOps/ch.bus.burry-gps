package ch.bus.gps.service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ch.bus.gps.dto.GpsDTO;
import ch.bus.gps.dto.SpeakingClockDTO;
import ch.bus.gps.entity.Pgps;
import ch.bus.gps.repository.PgpsRepository;

@Service
public class GpsService {

  private static Logger log = LoggerFactory.getLogger(GpsService.class);

  @Autowired
  private PgpsRepository pgpsRepository;

  private static List<GpsDTO> CACHED_TRIPE = new ArrayList<>();
  private static boolean RUNNING = true;
  private static Pgps LAST = null;

  private Date speakingClockDate = null;

  private boolean isNull(Double value) {
    return (value == null ? true : Double.isNaN(value));
  }

  @Transactional
  @RabbitListener(queues = "gps")
  public void receiveMessage(final GpsDTO gpsMessage) {
    if (gpsMessage == null || !RUNNING)
      return;

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
      this.pgpsRepository.save(pgps);
    }

    LAST = pgps;

  }

  public void stop() {
    RUNNING = false;
  }

  public GpsDTO getLast() {
    Pgps pgps = LAST;
    if (!Optional.ofNullable(pgps).isPresent())
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

    List<Pgps> r = this.pgpsRepository.getLast(250000);

    List<GpsDTO> list = new ArrayList<>();
    GpsDTO gpsDTO;

    int modulo = 1;
    if (r.size() > 5000)
      modulo = r.size() / 5000;


    for (int i = 0; i < r.size(); i++) {
      if (i % modulo != 0)
        continue;
      gpsDTO = new GpsDTO();
      gpsDTO.setLatitude(r.get(i).getCoordinate().getX());
      gpsDTO.setLongitude(r.get(i).getCoordinate().getY());
      list.add(gpsDTO);
    }

    CACHED_TRIPE = list;
  }

  public List<GpsDTO> getAll() {
    return CACHED_TRIPE;
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
