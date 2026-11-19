package com.aurevia.smartclinic;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

class SmartClinicApiApplicationTest {

    @Test
    void startsTheApplicationEntryPoint() {
        System.setProperty("spring.main.web-application-type", "none");
        try {
            SmartClinicApiApplication.main(new String[0]);
        } finally {
            System.clearProperty("spring.main.web-application-type");
        }
    }

    @Test
    void exposesTheTransactionTemplateBean() {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        assertNotNull(new SmartClinicApiApplication().transactionTemplate(transactionManager));
    }
}
