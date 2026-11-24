package com.aurevia.it.exchange.response;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

import com.aurevia.api.authorization.CommandCatalog;
import com.aurevia.exchange.response.ExchangeResponseActionPolicy;

/**
 * Guards the business-domain naming of the recipient-response slice.
 *
 * <p>Requirement IDs belong in Javadoc and tracker entries, never in a Java class
 * name (CODING_STYLE.md). This test proves the renamed policy class is the owner
 * the command catalog binds to the exchange commands and that the renamed types
 * are present under their business-domain names.</p>
 */
class ExchangeResponseNamingTest {

    @Test
    void exchangeCommandsAreOwnedByTheBusinessDomainPolicy() {
        CommandCatalog catalog = CommandCatalog.synthetic();
        assertEquals(ExchangeResponseActionPolicy.EXCHANGE_ACCEPT, catalog.require("acceptOrder").action());
        assertEquals(ExchangeResponseActionPolicy.EXCHANGE_REJECT, catalog.require("rejectOrder").action());
        assertEquals(CommandCatalog.EXCHANGE_RESPONSE_POLICY, catalog.require("acceptOrder").policy());
        assertEquals(CommandCatalog.EXCHANGE_RESPONSE_POLICY, catalog.require("rejectOrder").policy());
    }

    @Test
    void renamedProductionTypesArePresentUnderBusinessDomainNames() throws Exception {
        for (String simpleName : new String[] {"ExchangeResponseActionPolicy", "ExchangeResponseCommands",
                "ExchangeResponseController", "ExchangeResponseInputs", "ExchangeResponseRepository",
                "ExchangeResponseService"}) {
            assertNotNull(Class.forName("com.aurevia.exchange.response." + simpleName),
                    simpleName + " must exist under a business-domain name");
        }
    }
}
