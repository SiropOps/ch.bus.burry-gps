package ch.bus.gps.repository;

import java.util.Date;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import ch.bus.gps.entity.GpsPointFilteredByMinute;

public interface GpsPointFilteredByMinuteRepository
    extends JpaRepository<GpsPointFilteredByMinute, Date> {

  List<GpsPointFilteredByMinute> findAllByOrderByMinuteAsc();
}
