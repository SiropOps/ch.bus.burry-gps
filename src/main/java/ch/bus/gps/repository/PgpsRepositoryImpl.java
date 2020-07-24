package ch.bus.gps.repository;

import java.util.List;

import javax.persistence.EntityManager;
import javax.persistence.NoResultException;
import javax.persistence.PersistenceContext;

import org.hibernate.spatial.JTSGeometryType;
import org.hibernate.spatial.dialect.postgis.PGGeometryTypeDescriptor;
import org.springframework.stereotype.Repository;

import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.Point;

import ch.bus.gps.entity.Pgps;

@Repository
public class PgpsRepositoryImpl implements PgpsRepositoryCustom {

	@PersistenceContext
	EntityManager entityManager;

	@Override
	public Geometry createCircle(double longitude, double latitude, double error) {
		return (Geometry) entityManager.createNativeQuery(
				"SELECT ST_Buffer(ST_MakePoint(:longitude, :latitude)\\:\\:geography, :error)\\:\\:geometry as geom")
				.setParameter("longitude", longitude).setParameter("latitude", latitude).setParameter("error", error)
				.unwrap(org.hibernate.query.NativeQuery.class)
				.addScalar("geom", new JTSGeometryType(PGGeometryTypeDescriptor.INSTANCE)).getSingleResult();
	}

	@Override
	public Point createPoint(double longitude, double latitude) {
		return (Point) entityManager
				.createNativeQuery("SELECT ST_MakePoint(:longitude, :latitude)\\:\\:geography as geom")
				.setParameter("longitude", longitude).setParameter("latitude", latitude)
				.unwrap(org.hibernate.query.NativeQuery.class)
				.addScalar("geom", new JTSGeometryType(PGGeometryTypeDescriptor.INSTANCE)).getSingleResult();
	}

	@Override
	public boolean isTooClose(double longitude, double latitude) {
		return ((Boolean) entityManager.createNativeQuery("SELECT " + "ST_Contains(lastCercle, newCoordinate) "
				+ "FROM " + "(SELECT ST_MakePoint(:longitude, :latitude) AS newCoordinate, "
				+ "(SELECT ST_Buffer(ST_GeomFromText(ST_AsText(coordinate)), 0.0005) FROM pgps ORDER BY time DESC LIMIT 1) AS lastCercle) "
				+ "AS foo").setParameter("longitude", longitude).setParameter("latitude", latitude).getSingleResult())
						.booleanValue();
	}

	@Override
	public Pgps getLast() {
		try {
			return this.entityManager.createQuery("from Pgps p order by p.time desc nulls last", Pgps.class)
					.setMaxResults(1).getSingleResult();
		} catch (NoResultException e) {
			return null;
		}
	}

	@Override
	public List<Pgps> getLast(int nb) {
		try {
			return this.entityManager.createQuery("from Pgps p order by p.time desc nulls last", Pgps.class)
					.setMaxResults(nb).getResultList();
		} catch (NoResultException e) {
			return null;
		}
	}

}
