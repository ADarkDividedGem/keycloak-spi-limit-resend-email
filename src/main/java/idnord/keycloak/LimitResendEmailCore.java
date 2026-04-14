package idnord.keycloak;

import org.keycloak.common.util.Time;
import org.keycloak.models.UserModel;

public class LimitResendEmailCore {

    public static final String ATTR_FOR_LIMIT_RESEND_EMAIL_COUNT = "LimitResendEmailCount";
    public static final String ATTR_FOR_LIMIT_RESEND_EMAIL_LAST_TIME = "LimitResendEmailLastTime";
    public static final String MESSAGE_KEY_TOO_MANY_REQUESTS = "limitResendEmailTooManyRequestsError";
    public static final String FORM_ATTR_RETRIES_LEFT = "retriesLeft";

    public record Status(boolean blocked, int retriesLeft, int secondsUntilUnblocked) {
    }

    public static Status getStatus(UserModel user, final int LIMIT_RESEND_EMAIL_MAX_RETRIES, final int LIMIT_RESEND_EMAIL_BLOCK_DURATION_SECONDS) {

        int count = 0;
        int lastEmailSentTime = 0;

        try {
            String countAttr = user.getFirstAttribute(LimitResendEmailCore.ATTR_FOR_LIMIT_RESEND_EMAIL_COUNT);
            if (countAttr != null) {
                count = Integer.parseInt(countAttr);
            }
        } catch (Exception ignored) {
        }

        try {
            String lastTimeAttr = user.getFirstAttribute(LimitResendEmailCore.ATTR_FOR_LIMIT_RESEND_EMAIL_LAST_TIME);
            if (lastTimeAttr != null) {
                lastEmailSentTime = Integer.parseInt(lastTimeAttr);
            }
        } catch (Exception ignored) {
        }

        int retriesLeft = Math.max(0, LIMIT_RESEND_EMAIL_MAX_RETRIES - count);

        if (count < LIMIT_RESEND_EMAIL_MAX_RETRIES) {
            // send emails immediately until MAX_RETRIES, then only one email after each BLOCK_DURATION is allowed (see reset logic in listener)
            return new Status(false, retriesLeft, 0);
        }

        int elapsed = Time.currentTime() - lastEmailSentTime;
        int secondsUntilUnblocked = Math.max(0, LIMIT_RESEND_EMAIL_BLOCK_DURATION_SECONDS - elapsed);
        boolean blocked = secondsUntilUnblocked > 0;
        return new Status(blocked, 0, secondsUntilUnblocked);
    }

    public static boolean isLimitResendEmailReached(UserModel user, final int LIMIT_RESEND_EMAIL_MAX_RETRIES, final int LIMIT_RESEND_EMAIL_BLOCK_DURATION_SECONDS) {
        return getStatus(user, LIMIT_RESEND_EMAIL_MAX_RETRIES, LIMIT_RESEND_EMAIL_BLOCK_DURATION_SECONDS).blocked();
    }

}
