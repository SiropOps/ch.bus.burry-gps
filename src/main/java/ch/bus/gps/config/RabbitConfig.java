package ch.bus.gps.config;

import org.springframework.amqp.rabbit.annotation.RabbitListenerConfigurer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistrar;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.context.annotation.PropertySources;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.handler.annotation.support.DefaultMessageHandlerMethodFactory;
import org.springframework.messaging.handler.annotation.support.MessageHandlerMethodFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;

@Configuration
@PropertySources({
    @PropertySource(value = "rabbit-config.properties", ignoreResourceNotFound = true),
    @PropertySource(value = "file:/app/properties/rabbit-config.properties",
        ignoreResourceNotFound = true)})
public class RabbitConfig implements RabbitListenerConfigurer {

  @Override
  public void configureRabbitListeners(RabbitListenerEndpointRegistrar registrar) {
    registrar.setMessageHandlerMethodFactory(messageHandlerMethodFactory());
  }

  @Bean
  MessageHandlerMethodFactory messageHandlerMethodFactory() {
    DefaultMessageHandlerMethodFactory messageHandlerMethodFactory =
        new DefaultMessageHandlerMethodFactory();
    messageHandlerMethodFactory.setMessageConverter(consumerJackson2MessageConverter());
    return messageHandlerMethodFactory;
  }

  @Bean
  public MappingJackson2MessageConverter consumerJackson2MessageConverter() {
    MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter();
    ObjectMapper mapper = converter.getObjectMapper();
    mapper.configure(JsonParser.Feature.ALLOW_NON_NUMERIC_NUMBERS, true);
    return converter;
  }
}
