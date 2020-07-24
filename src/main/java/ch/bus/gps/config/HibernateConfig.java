package ch.bus.gps.config;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.context.annotation.PropertySources;

@Configuration
@EntityScan(value = {"ch.bus.gps"})
@PropertySources({@PropertySource(value = "credentials.properties", ignoreResourceNotFound = true),
    @PropertySource(value = "file:/app/properties/credentials.properties",
        ignoreResourceNotFound = true)})
public class HibernateConfig {

}
