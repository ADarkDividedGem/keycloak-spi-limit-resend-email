package idnord.keycloak.config;

import org.keycloak.models.RealmModel;

import java.util.Optional;

public class LimitResendEmailConfiguration {

    public static final String REALM_ATTR_MAX_RETRIES = "limitResendEmail.maxRetries";
    public static final String REALM_ATTR_RETRY_BLOCK_DURATION_IN_SEC = "limitResendEmail.retryBlockDurationInSec";

    public static final int DEFAULT_MAX_RETRIES;
    public static final int DEFAULT_RETRY_BLOCK_DURATION_IN_SEC;

    static {
        DEFAULT_MAX_RETRIES = Integer.parseInt(
                Optional.ofNullable(System.getenv("KEYCLOAK_LIMIT_RESEND_EMAIL_MAX_RETRIES")).orElse("3")
        );

        DEFAULT_RETRY_BLOCK_DURATION_IN_SEC = Integer.parseInt(
                Optional.ofNullable(System.getenv("KEYCLOAK_LIMIT_RESEND_EMAIL_RETRY_BLOCK_DURATION_IN_SEC")).orElse("3600")
        );
    }

    public record Values(int maxRetries, int retryBlockDurationInSec) {}

    public static Values resolve(RealmModel realm) {
        return new Values(
                parseIntOrDefault(realm.getAttribute(REALM_ATTR_MAX_RETRIES), DEFAULT_MAX_RETRIES),
                parseIntOrDefault(realm.getAttribute(REALM_ATTR_RETRY_BLOCK_DURATION_IN_SEC), DEFAULT_RETRY_BLOCK_DURATION_IN_SEC)
        );
    }

    private static int parseIntOrDefault(String value, int defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private LimitResendEmailConfiguration() {}
}
