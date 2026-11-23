package com.aurevia.smartclinic.web;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Decides whether the in-repository API documentation is exposed (specification section 7).
 *
 * <p>The feature is on for local, synthetic and explicitly enabled deployments
 * and off by default in production. It is a single source of truth shared by
 * the resource handler and both security chains so the page and its permit
 * rules cannot drift apart. The decision is fixed when the bean is created;
 * changing the environment later does not silently expose documentation.</p>
 */
@Component
public final class DocsEnabled {

    /** Operator switch; absent or false keeps production docs off. */
    static final String DOCS_PROPERTY = "smartclinic.docs.enabled";

    /** Local development profile that enables the page without an explicit switch. */
    static final String DEVELOPMENT_PROFILE = "development";

    /** Synthetic-header test profile that enables the page without an explicit switch. */
    static final String SYNTHETIC_PROFILE = "synthetic-auth";

    private final boolean enabled;

    /**
     * Evaluate the profile and property switches.
     *
     * @param environment Spring environment carrying active profiles and properties
     */
    public DocsEnabled(Environment environment) {
        this.enabled = environment.acceptsProfiles(Profiles.of(SYNTHETIC_PROFILE, DEVELOPMENT_PROFILE))
                || environment.getProperty(DOCS_PROPERTY, Boolean.class, false);
    }

    /**
     * Report whether the documentation page and its permit rules are active.
     *
     * @return {@code true} when the page must be served and permitted
     */
    public boolean isEnabled() {
        return enabled;
    }
}
