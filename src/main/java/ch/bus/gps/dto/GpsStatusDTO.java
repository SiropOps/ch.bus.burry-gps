package ch.bus.gps.dto;

import java.io.Serializable;
import java.util.Date;

public class GpsStatusDTO implements Serializable {

  private static final long serialVersionUID = 1L;

  private String gpsType;
  private boolean running;
  private Date lastSignalDate;

  public GpsStatusDTO() {}

  public GpsStatusDTO(String gpsType, boolean running, Date lastSignalDate) {
    this.gpsType = gpsType;
    this.running = running;
    this.lastSignalDate = lastSignalDate;
  }

  public String getGpsType() {
    return gpsType;
  }

  public void setGpsType(String gpsType) {
    this.gpsType = gpsType;
  }

  public boolean isRunning() {
    return running;
  }

  public void setRunning(boolean running) {
    this.running = running;
  }

  public Date getLastSignalDate() {
    return lastSignalDate;
  }

  public void setLastSignalDate(Date lastSignalDate) {
    this.lastSignalDate = lastSignalDate;
  }

  @Override
  public String toString() {
    return "GpsStatusDTO [gpsType=" + gpsType + ", running=" + running + ", lastSignalDate="
        + lastSignalDate + "]";
  }
}
