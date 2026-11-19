package com.aurevia.smartclinic;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Entry point for the tenant-isolated staff API. */
@SpringBootApplication
public class SmartClinicApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmartClinicApiApplication.class, args);
    }

    /** Shared template so every command runs inside the tenant-guarded transaction. */
    @Bean
    TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }
}
