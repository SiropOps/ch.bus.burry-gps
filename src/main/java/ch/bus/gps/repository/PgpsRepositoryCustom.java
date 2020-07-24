package ch.bus.gps.repository;

import java.util.List;

import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.Point;

import ch.bus.gps.entity.Pgps;

public interface PgpsRepositoryCustom {

	Geometry createCircle(double longitude, double latitude, double error);

	Point createPoint(double longitude, double latitude);

	boolean isTooClose(double longitude, double latitude);

	Pgps getLast();

	List<Pgps> getLast(int nb);
}
