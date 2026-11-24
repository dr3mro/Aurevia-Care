package com.aurevia.web;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

/**
 * Source-address allowlist for the unauthenticated metrics endpoint (WEB-06).
 *
 * <p>Loopback is always allowed so the local load balancer and orchestrator can
 * scrape the endpoint. Additional networks come from the comma-separated CIDR
 * list in {@code AUREVIA_METRICS_ALLOW}; with no configured value the policy
 * allows loopback only. A malformed CIDR fails construction so the application
 * never starts with a partially applied allowlist.</p>
 */
@Component
public final class MetricsAccessPolicy {

    /** Loopback sources, in CIDR form, always permitted. */
    private static final List<String> LOOPBACK = List.of("127.0.0.1/32", "::1/128");

    private final List<IpAddressMatcher> allowed;

    /**
     * Build the allowlist from loopback and the configured CIDR entries.
     *
     * @param cidrList comma-separated CIDRs from {@code AUREVIA_METRICS_ALLOW},
     *                 possibly empty; blank entries are ignored
     * @throws IllegalArgumentException when a configured CIDR is malformed
     */
    public MetricsAccessPolicy(@Value("${AUREVIA_METRICS_ALLOW:}") String cidrList) {
        List<String> sources = new ArrayList<>(LOOPBACK);
        sources.addAll(Arrays.stream(cidrList.split(","))
                .map(String::trim)
                .filter(entry -> !entry.isEmpty())
                .toList());
        this.allowed = sources.stream().map(IpAddressMatcher::new).toList();
    }

    /**
     * Decide whether a remote address may scrape the metrics endpoint.
     *
     * @param remoteAddress remote address reported by the servlet container
     * @return {@code true} when the address matches loopback or a configured CIDR
     */
    public boolean allows(String remoteAddress) {
        return allowed.stream().anyMatch(matcher -> matcher.matches(remoteAddress));
    }
}
