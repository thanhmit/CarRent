package com.carrent.config;

import com.carrent.booking.RentalDeadlineJob;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "carrent.rental.deadlines.enabled", havingValue = "true")
public class RentalDeadlineConfiguration {
    @Bean Tick rentalDeadlineTick(RentalDeadlineJob job) { return new Tick(job); }

    static class Tick {
        private final RentalDeadlineJob job;
        Tick(RentalDeadlineJob job) { this.job = job; }
        @Scheduled(fixedDelay = 30000)
        public void scan() { job.scan(); }
    }
}
