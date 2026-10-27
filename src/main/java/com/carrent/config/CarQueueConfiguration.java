package com.carrent.config;

import com.carrent.concurrency.CarCommandExecutor;
import com.carrent.concurrency.CarQueueProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CarQueueProperties.class)
public class CarQueueConfiguration {
    @Bean(destroyMethod = "close")
    public CarCommandExecutor carCommandExecutor(CarQueueProperties properties) {
        return new CarCommandExecutor(properties);
    }
}
