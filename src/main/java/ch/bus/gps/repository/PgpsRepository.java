package ch.bus.gps.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import ch.bus.gps.entity.Pgps;

public interface PgpsRepository extends JpaRepository<Pgps, Long>, PgpsRepositoryCustom {

}
