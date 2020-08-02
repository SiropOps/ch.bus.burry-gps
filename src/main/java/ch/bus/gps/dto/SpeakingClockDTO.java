package ch.bus.gps.dto;

import java.io.Serializable;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;

public class SpeakingClockDTO implements Serializable {

  private static final long serialVersionUID = 1L;

  private static final String PATTERN_FR = "dd-MM-yyyy'T'HH:mm:ss.SSS'Z'";
  private static final DateFormat DATE_FORMAT_FR = new SimpleDateFormat(PATTERN_FR);

  private Date date;
  private String fr;


  public SpeakingClockDTO() {}

  public SpeakingClockDTO(Date date) {
    if (date == null)
      throw new IllegalArgumentException("Date is null");
    this.date = date;
    this.fr = DATE_FORMAT_FR.format(date);
  }

  public String getFr() {
    return fr;
  }

  public void setFr(String fr) {
    this.fr = fr;
  }

  public Date getDate() {
    return date;
  }

  public void setDate(Date date) {
    this.date = date;
  }

  @Override
  public String toString() {
    return "SpeakingClockDTO [date=" + date + ", fr=" + fr + "]";
  }

}
