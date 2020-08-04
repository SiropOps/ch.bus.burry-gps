package ch.bus.gps.dto;

import java.io.Serializable;
import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;

public class GpsDTO implements Serializable {

  private static final long serialVersionUID = 1L;

  private Double latitude;// Latitude in degrees
  private Double epy;// Estimated latitude error - meters

  private Double longitude;// Longitude in degrees
  private Double epx;// Estimated longitude error - meters

  private Double altitude;// Altitude - meters
  private Double epv;// Estimated altitude error - meters

  private Double speed;// Speed over ground - meters per second
  private Double eps;// Estimated speed error - meters per second

  @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
  private Date time;// Time
  private Double ept;// Estimated timestamp error - seconds

  private Double climb;// Climb velocity - meters per second
  private Double epc;// Estimated climb error - meters per seconds

  private Double track;// Direction - degrees from true north
  private Double epd;// Estimated direction error - degrees

  private Integer mode;

  public Double getLatitude() {
    return latitude;
  }

  public void setLatitude(Double latitude) {
    this.latitude = latitude;
  }

  public Double getEpy() {
    return epy;
  }

  public void setEpy(Double epy) {
    this.epy = epy;
  }

  public Double getLongitude() {
    return longitude;
  }

  public void setLongitude(Double longitude) {
    this.longitude = longitude;
  }

  public Double getEpx() {
    return epx;
  }

  public void setEpx(Double epx) {
    this.epx = epx;
  }

  public Double getAltitude() {
    return altitude;
  }

  public void setAltitude(Double altitude) {
    this.altitude = altitude;
  }

  public Double getEpv() {
    return epv;
  }

  public void setEpv(Double epv) {
    this.epv = epv;
  }

  public Double getSpeed() {
    return speed;
  }

  public void setSpeed(Double speed) {
    this.speed = speed;
  }

  public Double getEps() {
    return eps;
  }

  public void setEps(Double eps) {
    this.eps = eps;
  }

  public Date getTime() {
    return time;
  }

  public void setTime(Date time) {
    this.time = time;
  }

  public Double getEpt() {
    return ept;
  }

  public void setEpt(Double ept) {
    this.ept = ept;
  }

  public Double getClimb() {
    return climb;
  }

  public void setClimb(Double climb) {
    this.climb = climb;
  }

  public Double getEpc() {
    return epc;
  }

  public void setEpc(Double epc) {
    this.epc = epc;
  }

  public Double getTrack() {
    return track;
  }

  public void setTrack(Double track) {
    this.track = track;
  }

  public Double getEpd() {
    return epd;
  }

  public void setEpd(Double epd) {
    this.epd = epd;
  }

  public Integer getMode() {
    return mode;
  }

  public void setMode(Integer mode) {
    this.mode = mode;
  }

  @Override
  public String toString() {
    return "GpsDTO [latitude=" + latitude + ", epy=" + epy + ", longitude=" + longitude + ", epx="
        + epx + ", altitude=" + altitude + ", epv=" + epv + ", speed=" + speed + ", eps=" + eps
        + ", time=" + time + ", ept=" + ept + ", climb=" + climb + ", epc=" + epc + ", track="
        + track + ", epd=" + epd + ", mode=" + mode + "]";
  }

}
