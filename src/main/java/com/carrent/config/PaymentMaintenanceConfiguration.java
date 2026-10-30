package com.carrent.config;

import com.carrent.payment.PaymentMaintenanceJob;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
public class PaymentMaintenanceConfiguration {
    @Bean public ThreadPoolTaskExecutor paymentDomainExecutor() { return executor("payment-domain-"); }
    @Bean public ThreadPoolTaskExecutor paymentPayoutExecutor() { return executor("payment-payout-"); }

    private static ThreadPoolTaskExecutor executor(String prefix) {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix(prefix);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(name = "carrent.payment.maintenance.enabled", havingValue = "true")
    static class Schedule {
        @Bean Tick paymentMaintenanceTick(PaymentMaintenanceJob job) { return new Tick(job); }
    }

    static class Tick {
        private final PaymentMaintenanceJob job;
        Tick(PaymentMaintenanceJob job) { this.job = job; }
        @Scheduled(fixedDelay = 30000)
        public void scan() { job.scan(); }
    }
}
