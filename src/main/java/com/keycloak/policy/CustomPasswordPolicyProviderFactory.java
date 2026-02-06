package com.keycloak.policy;

import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.policy.PasswordPolicyProvider;
import org.keycloak.policy.PasswordPolicyProviderFactory;

/**
 * Factory for creating CustomPasswordPolicyProvider instances
 */
public class CustomPasswordPolicyProviderFactory implements PasswordPolicyProviderFactory {

    public static final String ID = "custom-password-policy";
    public static final String DISPLAY_NAME = "Custom Password Policy";
    public static final String CONFIG_TYPE = "int";
    public static final String DEFAULT_VALUE = "12";

    @Override
    public PasswordPolicyProvider create(KeycloakSession session) {
        return new CustomPasswordPolicyProvider(session.getContext());
    }

    @Override
    public void init(Config.Scope config) {
        // No initialization needed
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // No post-initialization needed
    }

    @Override
    public void close() {
        // No resources to close
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayName() {
        return DISPLAY_NAME;
    }

    @Override
    public String getConfigType() {
        return CONFIG_TYPE;
    }

    @Override
    public String getDefaultConfigValue() {
        return DEFAULT_VALUE;
    }

    @Override
    public boolean isMultiplSupported() {
        return false;
    }
}
