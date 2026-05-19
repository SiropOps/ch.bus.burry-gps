package ch.bus.gps.repository;

import java.util.Date;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;
import ch.bus.gps.entity.GpsPointFilteredByMinute;

public interface GpsPointFilteredByMinuteRepository
    extends JpaRepository<GpsPointFilteredByMinute, Date> {

  @Modifying
  @Transactional
  @Query(value = "REFRESH MATERIALIZED VIEW vm_gps_points_filtered_by_minute WITH DATA",
      nativeQuery = true)
  void refreshMaterializedView();

  List<GpsPointFilteredByMinute> findAllByOrderByMinuteAsc();
}
