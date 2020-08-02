package ch.bus.gps.config;

import org.springframework.context.annotation.PropertySource;
import org.springframework.context.annotation.PropertySources;

@PropertySources({
    @PropertySource(value = "config.properties", ignoreResourceNotFound = true),
    @PropertySource(value = "file:/app/properties/config.properties",
        ignoreResourceNotFound = true),
    @PropertySource(value = "credentials.properties", ignoreResourceNotFound = true),
    @PropertySource(value = "file:/app/credentials/credentials.properties",
        ignoreResourceNotFound = true)})
public class PropertiesFileConfig {

}
