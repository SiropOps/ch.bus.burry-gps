package ch.bus.gps;

import java.util.concurrent.Executor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import ch.bus.gps.service.GpsService;

@EnableAsync
@EnableScheduling
@EnableFeignClients
@SpringBootApplication
public class BurryGps {

  public static void main(String[] args) {
    SpringApplication.run(BurryGps.class, args);
  }

  @Bean
  public Executor taskExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(5);
    executor.setMaxPoolSize(5);
    executor.setQueueCapacity(500);
    executor.setThreadNamePrefix("BurryGpsLookup-");
    executor.initialize();
    return executor;
  }

  @Bean
  @Primary
  public ObjectMapper objectMapper() {
    Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
    builder.featuresToEnable(JsonParser.Feature.ALLOW_NON_NUMERIC_NUMBERS);
    builder.serializationInclusion(JsonInclude.Include.NON_EMPTY);
    return builder.build();
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
