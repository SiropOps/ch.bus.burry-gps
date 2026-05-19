package ch.bus.gps.entity;

import java.io.Serializable;
import java.util.Date;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.locationtech.jts.geom.Point;

@Entity
@Immutable
@Table(name = "vm_gps_points_filtered_by_minute", schema = "public")
public class GpsPointFilteredByMinute implements Serializable {

  private static final long serialVersionUID = 1L;

  private Date minute;
  private Double avgSpeed;
  private Double avgSpeedError;
  private Point coordinateAvgGeom;

  @Id
  @Column(name = "minute")
  public Date getMinute() {
    return minute;
  }

  public void setMinute(Date minute) {
    this.minute = minute;
  }

  @Column(name = "avg_speed")
  public Double getAvgSpeed() {
    return avgSpeed;
  }

  public void setAvgSpeed(Double avgSpeed) {
    this.avgSpeed = avgSpeed;
  }

  @Column(name = "avg_speed_error")
  public Double getAvgSpeedError() {
    return avgSpeedError;
  }

  public void setAvgSpeedError(Double avgSpeedError) {
    this.avgSpeedError = avgSpeedError;
  }

  @Column(name = "coordinate_avg_geom", columnDefinition = "geometry")
  public Point getCoordinateAvgGeom() {
    return coordinateAvgGeom;
  }

  public void setCoordinateAvgGeom(Point coordinateAvgGeom) {
    this.coordinateAvgGeom = coordinateAvgGeom;
  }

}
