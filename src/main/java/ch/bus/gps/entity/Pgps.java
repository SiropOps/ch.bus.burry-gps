package ch.bus.gps.entity;

import java.io.Serializable;
import java.util.Date;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;
import org.locationtech.jts.geom.Point;

@Entity
@Table(name = "pgps")
public class Pgps implements Serializable {

  private static final long serialVersionUID = 1L;

  private long id;
  private Point coordinate;

  private Double latitudeError; // Estimated latitude error - meters
  private Double longitudeError; // Estimated longitude error - meters

  private Double altitude; // Altitude - meters
  private Double altitudeError; // Estimated altitude error - meters

  private Double speed; // Speed over ground - meters per second
  private Double speedError; // Estimated speed error - meters per second

  private Date time; // Time

  private Double climb; // Climb velocity - meters per second
  private Double climbError; // Estimated climb error - meters per seconds

  private Double track; // Direction - degrees from true north
  private Double trackError; // Estimated direction error - degrees

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id_pgps")
  public long getId() {
    return id;
  }

  public void setId(long id) {
    this.id = id;
  }

  @Column(name = "coordinate", columnDefinition = "geometry")
  public Point getCoordinate() {
    return coordinate;
  }

  public void setCoordinate(Point coordinate) {
    this.coordinate = coordinate;
  }

  @Column(name = "latitude_error")
  public Double getLatitudeError() {
    return latitudeError;
  }

  public void setLatitudeError(Double latitudeError) {
    this.latitudeError = latitudeError;
  }

  @Column(name = "longitude_error")
  public Double getLongitudeError() {
    return longitudeError;
  }

  public void setLongitudeError(Double longitudeError) {
    this.longitudeError = longitudeError;
  }

  @Column(name = "altitude")
  public Double getAltitude() {
    return altitude;
  }

  public void setAltitude(Double altitude) {
    this.altitude = altitude;
  }

  @Column(name = "altitude_error")
  public Double getAltitudeError() {
    return altitudeError;
  }

  public void setAltitudeError(Double altitudeError) {
    this.altitudeError = altitudeError;
  }

  @Column(name = "speed")
  public Double getSpeed() {
    return speed;
  }

  public void setSpeed(Double speed) {
    this.speed = speed;
  }

  @Column(name = "speed_error")
  public Double getSpeedError() {
    return speedError;
  }

  public void setSpeedError(Double speedError) {
    this.speedError = speedError;
  }

  @Column(name = "time")
  public Date getTime() {
    return time;
  }

  public void setTime(Date time) {
    this.time = time;
  }

  @Column(name = "climb")
  public Double getClimb() {
    return climb;
  }

  public void setClimb(Double climb) {
    this.climb = climb;
  }

  @Column(name = "climb_error")
  public Double getClimbError() {
    return climbError;
  }

  public void setClimbError(Double climbError) {
    this.climbError = climbError;
  }

  @Column(name = "track")
  public Double getTrack() {
    return track;
  }

  public void setTrack(Double track) {
    this.track = track;
  }

  @Column(name = "track_error")
  public Double getTrackError() {
    return trackError;
  }

  public void setTrackError(Double trackError) {
    this.trackError = trackError;
  }

  @Override
  public String toString() {
    return "Pgps [id=" + id + ", coordinate=" + coordinate + ", latitudeError=" + latitudeError
        + ", longitudeError=" + longitudeError + ", altitude=" + altitude + ", altitudeError="
        + altitudeError + ", speed=" + speed + ", speedError=" + speedError + ", time=" + time
        + ", climb=" + climb + ", climbError=" + climbError + ", track=" + track + ", trackError="
        + trackError + "]";
  }

}
