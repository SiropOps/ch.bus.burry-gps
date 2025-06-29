package ch.bus.gps.component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import ch.bus.gps.entity.Pgps;
import ch.bus.gps.repository.PgpsRepository;

@Component
public class GpsComponent {

  private static Logger log = LoggerFactory.getLogger(GpsComponent.class);

  @Autowired
  private PgpsRepository pgpsRepository;

  private static final List<Pgps> LIST_PGPS = new ArrayList<>();

  @Async
  public synchronized void addOrSave(Pgps pgps) {

    if (Optional.ofNullable(pgps).isEmpty()) {
      log.info("Saving most precise: {}", LIST_PGPS.size());
      this.findMostPrecisePgps().ifPresent(p -> {
        log.debug("Saving most precise Pgps: {}", p);
        this.pgpsRepository.save(p);
      });
      LIST_PGPS.clear();
    } else {
      log.debug("ADDED : {}", pgps);
      LIST_PGPS.add(pgps);
    }

  }


  private Optional<Pgps> findMostPrecisePgps() {
    return LIST_PGPS.stream()
        .filter(p -> p.getLatitudeError() != null && p.getLongitudeError() != null)
        .min(Comparator.comparingDouble(p -> p.getLatitudeError() + p.getLongitudeError()));
  }

}
