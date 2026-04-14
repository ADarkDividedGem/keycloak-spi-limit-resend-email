package idnord.keycloak.requiredaction;

import com.google.auto.service.AutoService;
import idnord.keycloak.LimitResendEmailCore;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.UriBuilderException;
import jakarta.ws.rs.core.UriInfo;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.authentication.AuthenticationProcessor;
import org.keycloak.authentication.RequiredActionContext;
import org.keycloak.authentication.RequiredActionFactory;
import org.keycloak.authentication.RequiredActionProvider;
import org.keycloak.authentication.actiontoken.verifyemail.VerifyEmailActionToken;
import org.keycloak.authentication.requiredactions.VerifyEmail;
import org.keycloak.common.util.Time;
import org.keycloak.email.EmailException;
import org.keycloak.email.EmailTemplateProvider;
import org.keycloak.events.Details;
import org.keycloak.events.Errors;
import org.keycloak.events.EventBuilder;
import org.keycloak.events.EventType;
import org.keycloak.models.Constants;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.services.Urls;
import org.keycloak.services.messages.Messages;
import org.keycloak.services.validation.Validation;
import org.keycloak.sessions.AuthenticationSessionCompoundId;
import org.keycloak.sessions.AuthenticationSessionModel;

import java.util.concurrent.TimeUnit;

import static idnord.keycloak.LimitResendEmailCore.FORM_ATTR_RETRIES_LEFT;
import static idnord.keycloak.LimitResendEmailCore.MESSAGE_KEY_TOO_MANY_REQUESTS;
import static idnord.keycloak.config.LimitResendEmailConfiguration.LIMIT_RESEND_EMAIL_MAX_RETRIES;
import static idnord.keycloak.config.LimitResendEmailConfiguration.LIMIT_RESEND_EMAIL_RETRY_BLOCK_DURATION_IN_SEC;

@Slf4j
@AutoService(RequiredActionFactory.class)
public class CustomVerifyEmail extends VerifyEmail implements RequiredActionProvider, RequiredActionFactory {

    /**
     * Applies protection logic before displaying a page that automatically triggers an email.
     * For example, after a successful login, the verification page should not be shown
     * if the email send limit has already been reached.
     */
    @Override
    public void requiredActionChallenge(RequiredActionContext context) {
        handle(context);
    }

    /**
     * Applies protection logic after the user clicks "Resend Verification Email".
     * For example, if a resend limit has been reached, the page with the resend link should not be displayed.
     */
    @Override
    public void processAction(RequiredActionContext context) {
        // Intentional resend click — clear the anti-refresh marker so handle() does a fresh send.
        context.getAuthenticationSession().removeAuthNote(Constants.VERIFY_EMAIL_KEY);
        handle(context);
    }

    @Override
    public RequiredActionProvider create(KeycloakSession session) {
        return this;
    }

    @Override
    public String getId() {
        return super.getId();
    }

    private void handle(RequiredActionContext context) {
        UserModel user = context.getUser();
        LimitResendEmailCore.Status status = LimitResendEmailCore.getStatus(
                user,
                LIMIT_RESEND_EMAIL_MAX_RETRIES,
                LIMIT_RESEND_EMAIL_RETRY_BLOCK_DURATION_IN_SEC
        );

        if (status.blocked()) {
            handleReachedLimit(context, user, status);
            return;
        }

        if (user.isEmailVerified()) {
            context.success();
            context.getAuthenticationSession().removeAuthNote(Constants.VERIFY_EMAIL_KEY);
            return;
        }

        String email = user.getEmail();
        if (Validation.isBlank(email)) {
            context.ignore();
            return;
        }

        AuthenticationSessionModel authSession = context.getAuthenticationSession();
        if (email.equals(authSession.getAuthNote(Constants.VERIFY_EMAIL_KEY))) {
            // Page refresh: re-render without re-sending. The listener already decremented the
            // counter on the previous send, so status.retriesLeft() is already the post-send value.
            context.challenge(context.form()
                    .setAttribute(FORM_ATTR_RETRIES_LEFT, status.retriesLeft())
                    .createResponse(UserModel.RequiredAction.VERIFY_EMAIL));
            return;
        }
        authSession.setAuthNote(Constants.VERIFY_EMAIL_KEY, email);

        // The listener increments the count once SEND_VERIFY_EMAIL fires, so display the
        // post-send value ("N attempts remaining before sending is temporarily blocked").
        int retriesLeftAfterSend = Math.max(0, status.retriesLeft() - 1);

        EventBuilder event = context.getEvent().clone()
                .event(EventType.SEND_VERIFY_EMAIL)
                .detail(Details.EMAIL, email);

        context.challenge(sendVerifyEmail(context, event, retriesLeftAfterSend));
    }

    // Inlined from VerifyEmail#sendVerifyEmail (made private in Keycloak 26.5.4) so we can inject
    // the retriesLeft form attribute into the rendered response.
    private Response sendVerifyEmail(RequiredActionContext context, EventBuilder event, int retriesLeftAfterSend)
            throws UriBuilderException, IllegalArgumentException {
        RealmModel realm = context.getRealm();
        UriInfo uriInfo = context.getUriInfo();
        UserModel user = context.getUser();
        AuthenticationSessionModel authSession = context.getAuthenticationSession();
        KeycloakSession session = context.getSession();

        int validityInSecs = realm.getActionTokenGeneratedByUserLifespan(VerifyEmailActionToken.TOKEN_TYPE);
        int absoluteExpirationInSecs = Time.currentTime() + validityInSecs;

        String authSessionEncodedId = AuthenticationSessionCompoundId.fromAuthSession(authSession).getEncodedId();
        VerifyEmailActionToken token = new VerifyEmailActionToken(user.getId(), absoluteExpirationInSecs, authSessionEncodedId, user.getEmail(), authSession.getClient().getClientId());
        UriBuilder builder = Urls.actionTokenBuilder(uriInfo.getBaseUri(), token.serialize(session, realm, uriInfo),
                authSession.getClient().getClientId(), authSession.getTabId(), AuthenticationProcessor.getClientData(session, authSession));
        String link = builder.build(realm.getName()).toString();
        long expirationInMinutes = TimeUnit.SECONDS.toMinutes(validityInSecs);

        try {
            session
                    .getProvider(EmailTemplateProvider.class)
                    .setAuthenticationSession(authSession)
                    .setRealm(realm)
                    .setUser(user)
                    .sendVerifyEmail(link, expirationInMinutes);
            event.success();

            return context.form()
                    .setAttribute(FORM_ATTR_RETRIES_LEFT, retriesLeftAfterSend)
                    .createResponse(UserModel.RequiredAction.VERIFY_EMAIL);
        } catch (EmailException e) {
            event.clone().event(EventType.SEND_VERIFY_EMAIL)
                    .detail(Details.REASON, e.getMessage())
                    .user(user)
                    .error(Errors.EMAIL_SEND_FAILED);
            log.error("Failed to send verification email", e);
            context.failure(Messages.EMAIL_SENT_ERROR);
            return context.form()
                    .setError(Messages.EMAIL_SENT_ERROR)
                    .createErrorPage(Response.Status.INTERNAL_SERVER_ERROR);
        }
    }

    private void handleReachedLimit(RequiredActionContext context, UserModel user, LimitResendEmailCore.Status status) {
        int minutesLeft = (int) Math.ceil(status.secondsUntilUnblocked() / 60.0);

        context.getEvent().clone().event(EventType.SEND_VERIFY_EMAIL)
                .detail(Details.REASON, "Too many emails sent. Please wait before trying again.")
                .user(user)
                .error(Errors.EMAIL_SEND_FAILED);

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
    }

}
