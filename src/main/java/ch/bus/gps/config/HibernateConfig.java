package ch.bus.gps.config;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@EntityScan(value = {"ch.bus.gps"})
public class HibernateConfig {

}
