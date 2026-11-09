package com.carrent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class CarRentApplication {

    public static void main(String[] args) {
        var context = SpringApplication.run(CarRentApplication.class, args);
        if (context.getEnvironment().matchesProfiles("sepay-setup", "sepay-activate", "payos-check", "payos-setup", "refund-lookup-check")) {
            context.close();
        }
    }
}
