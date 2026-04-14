package idnord.keycloak.authenticator;

import idnord.keycloak.LimitResendEmailCore;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.Authenticator;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

import static idnord.keycloak.LimitResendEmailCore.FORM_ATTR_RETRIES_LEFT;
import static idnord.keycloak.LimitResendEmailCore.MESSAGE_KEY_TOO_MANY_REQUESTS;
import static idnord.keycloak.config.LimitResendEmailConfiguration.LIMIT_RESEND_EMAIL_MAX_RETRIES;
import static idnord.keycloak.config.LimitResendEmailConfiguration.LIMIT_RESEND_EMAIL_RETRY_BLOCK_DURATION_IN_SEC;

@Slf4j
public class LimitResendEmailAuthenticator implements Authenticator {

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        UserModel user = context.getUser();

        LimitResendEmailCore.Status status = LimitResendEmailCore.getStatus(user, LIMIT_RESEND_EMAIL_MAX_RETRIES, LIMIT_RESEND_EMAIL_RETRY_BLOCK_DURATION_IN_SEC);
        if (status.blocked()) {
            int minutesLeft = (int) Math.ceil(status.secondsUntilUnblocked() / 60.0);

            log.info("Email sending limited for username={}, clientId={}, IP={}, secondsUntilUnblocked={}.",
                    user.getUsername(), context.getAuthenticationSession().getClient().getClientId(), context.getSession().getContext().getConnection().getRemoteAddr(), status.secondsUntilUnblocked());

            context.challenge(
                    context.form()
                            .setAttribute(FORM_ATTR_RETRIES_LEFT, status.retriesLeft())
                            .setAttribute("secondsUntilUnblocked", status.secondsUntilUnblocked())
                            .setAttribute("minutesUntilUnblocked", minutesLeft)
                            .setError(MESSAGE_KEY_TOO_MANY_REQUESTS, minutesLeft)
                            .createErrorPage(Response.Status.TOO_MANY_REQUESTS)
            );

        } else {
            context.success();
        }
    }

    @Override
    public void action(AuthenticationFlowContext context) {
    }

    @Override
    public boolean requiresUser() {
        return true;
    }

    @Override
    public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
        return true;
    }

    @Override
    public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {
    }

    @Override
    public void close() {
    }
}
