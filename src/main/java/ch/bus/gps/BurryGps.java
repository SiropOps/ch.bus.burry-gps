package ch.bus.gps;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import ch.bus.gps.service.GpsService;

@EnableAsync
@EnableScheduling
@EnableFeignClients
@SpringBootApplication
public class BurryGps {

  public static void main(String[] args) {
    SpringApplication.run(BurryGps.class, args);
  }

  @Autowired(required = false)
  private GpsService gpsService;


  @Bean
  synchronized InitializingBean uploadCachedList() {
    return () -> {
      if (this.gpsService != null) {
        this.gpsService.getAllInCache();
      }
    };
  }
}
