package com.aurevia;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

class OreviaApiApplicationTest {

    @Test
    void startsTheApplicationEntryPoint() {
        System.setProperty("spring.main.web-application-type", "none");
        try {
            OreviaApiApplication.main(new String[0]);
        } finally {
            System.clearProperty("spring.main.web-application-type");
        }
    }

    @Test
    void exposesTheTransactionTemplateBean() {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        assertNotNull(new OreviaApiApplication().transactionTemplate(transactionManager));
    }
}
