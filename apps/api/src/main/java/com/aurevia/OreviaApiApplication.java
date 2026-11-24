package com.aurevia;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Entry point for the tenant-isolated staff API.
 */
@SpringBootApplication
public class OreviaApiApplication {

    /**
     * Start the application.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(OreviaApiApplication.class, args);
    }

    /**
     * Share one transaction template so every command uses the guarded manager.
     *
     * @param transactionManager the tenant-guarded platform transaction manager
     * @return shared transaction template
     */
    @Bean
    TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }
}
