# keycloak-spi-limit-resend-email
[![jar](https://img.shields.io/github/v/tag/idNoRD/keycloak-spi-limit-resend-email?label=jar&logo=openjdk&logoColor=000000&labelColor=ED8B00)](https://github.com/idNoRD/keycloak-spi-limit-resend-email/releases/latest)
[![Build & Test](https://github.com/idNoRD/keycloak-spi-limit-resend-email/actions/workflows/build.yml/badge.svg)](https://github.com/idNoRD/keycloak-spi-limit-resend-email/actions/workflows/build.yml)
![GitHub Maintained](https://img.shields.io/maintenance/yes/2026)
![GitHub License](https://img.shields.io/github/license/ironwolphern/ansible-role-certbot)
[![Keycloak](https://img.shields.io/badge/Keycloak-26.5.4-blue)](https://github.com/keycloak/keycloak/releases)
---
Keycloak spi that 
- limits resend email verification 
([#192341](https://github.com/keycloak/keycloak/issues/19234), 
 [#24558](https://github.com/keycloak/keycloak/issues/24558))
- limits forgot password emails
([#24914](https://github.com/keycloak/keycloak/issues/24914), 
[#26182](https://github.com/keycloak/keycloak/issues/26182), 
[#16574](https://github.com/keycloak/keycloak/issues/16574))
- limits email sending after each successful login with an unverified email
---

## 🛠 Installation
### 1. Download a jar from [Releases](https://github.com/idNoRD/keycloak-spi-limit-resend-email/releases/latest)
### 2. Copy jar to /opt/keycloak/providers/
### 3. Rebuild Keycloak `/opt/keycloak/bin/kc.sh build`
### 4. Configure the provider using the following environment variables:
```text
KEYCLOAK_LIMIT_RESEND_EMAIL_MAX_RETRIES=3
KEYCLOAK_LIMIT_RESEND_EMAIL_RETRY_BLOCK_DURATION_IN_SEC=3600
```
| Environment Variable                                       | Description                                                                                                                                                                                                  | Default Value |
|------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------|
| `KEYCLOAK_LIMIT_RESEND_EMAIL_MAX_RETRIES`                 | Maximum number of attempts to send verification or reset password emails without delay. After reaching this limit, email sending will be temporarily blocked until the user verifies their email or resets the password. | `3`           |
| `KEYCLOAK_LIMIT_RESEND_EMAIL_RETRY_BLOCK_DURATION_IN_SEC` | Duration (in seconds) of the block after exceeding the retry limit. After this period, the user may send one more email before being blocked again, unless they verify or reset their password.               | `3600`        |

#### Overriding per realm without restarting Keycloak
Both settings can be overridden per realm via **realm attributes**, which take effect immediately (no restart, no rebuild). The env-var values act as the fallback/default when a realm attribute is not set.

| Realm Attribute                           | Overrides                                                 |
|-------------------------------------------|-----------------------------------------------------------|
| `limitResendEmail.maxRetries`             | `KEYCLOAK_LIMIT_RESEND_EMAIL_MAX_RETRIES`                 |
| `limitResendEmail.retryBlockDurationInSec`| `KEYCLOAK_LIMIT_RESEND_EMAIL_RETRY_BLOCK_DURATION_IN_SEC` |

Resolution order: **realm attribute → env var → built-in default (`3` / `3600`)**.

Set via Admin REST API:
```bash
curl -X PUT "$KC_URL/admin/realms/$REALM" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"attributes":{"limitResendEmail.maxRetries":"5","limitResendEmail.retryBlockDurationInSec":"1800"}}'
```

Or via `kcadm.sh` / `kcadm.bat`:
```bash
kcadm.sh update realms/$REALM \
  -s 'attributes."limitResendEmail.maxRetries"=5' \
  -s 'attributes."limitResendEmail.retryBlockDurationInSec"=1800'
```

Or via Terraform:
```terraform
resource "keycloak_realm" "realm" {
  # ...
  attributes = {
    "limitResendEmail.maxRetries"              = "5"
    "limitResendEmail.retryBlockDurationInSec" = "1800"
  }
}
```

### 5. Restart Keycloak
### 6. Configure Realm
#### 6.1 Open master realm and check that "Provider info" contains
  - eventsListener contains limit-resend-email-event
  - authenticator contains limit-resend-email-authenticator
  - required-action contains VERIFY_EMAIL
#### 6.2 Go to "Realm Settings" of your realm → Events → Event Listeners and add `limit-resend-email-event` and click Save.
<details>

<summary>or configure 6.2 section using terraform</summary>

[tested using keycloak Provider 5.2.0](https://registry.terraform.io/providers/keycloak/keycloak/5.2.0/docs)


```terraform
resource "keycloak_realm_events" "realm_events" {
  realm_id = keycloak_realm.realm.id
  ...
  events_listeners = [
    "jboss-logging",
    "limit-resend-email-event" # <---
  ]
}
```
</details>

#### 6.3.1 Go to "Authentication" → "Flows" tab and Duplicate the **"reset credentials"** flow and insert **"LimitResendEmail Authenticator"** (mark as **Required**). 
#### 6.3.2 Reorder and Place the **"LimitResendEmail Authenticator"** above **"Send Reset Email"** so that it runs before "Send Reset Email".
#### 6.3.4 In your duplicated flow click Action → "Bind flow" to set it as the active **"Reset credentials flow"** .

<details>
<summary>or configure 6.3 section using terraform</summary>

[tested using keycloak Provider 5.2.0](https://registry.terraform.io/providers/keycloak/keycloak/5.2.0/docs)


```terraform
resource "keycloak_authentication_flow" "custom_reset_credentials_flow" {
  realm_id    = keycloak_realm.realm.id
  alias       = "custom-reset-credentials"
  description = "Custom copy of default reset_credentials flow"
  provider_id = "basic-flow"
  depends_on  = [keycloak_realm.realm]
}

# Step 1: Choose User (username-lookup)
resource "keycloak_authentication_execution" "reset-credentials-choose-user" {
  realm_id          = keycloak_realm.realm.id
  parent_flow_alias = keycloak_authentication_flow.custom_reset_credentials_flow.alias
  authenticator     = "reset-credentials-choose-user"
  requirement       = "REQUIRED"
  priority          = 10
  depends_on        = [keycloak_authentication_flow.custom_reset_credentials_flow]
}

resource "keycloak_authentication_execution" "limit-resend-email-authenticator" {
  realm_id          = keycloak_realm.realm.id
  parent_flow_alias = keycloak_authentication_flow.custom_reset_credentials_flow.alias
  authenticator     = "limit-resend-email-authenticator"
  requirement       = "REQUIRED"
  priority          = 18
  depends_on        = [keycloak_authentication_execution.reset-credentials-choose-user]
}

# Step 2: Send Reset Email (reset-credential)
resource "keycloak_authentication_execution" "reset-credential-email" {
  realm_id          = keycloak_realm.realm.id
  parent_flow_alias = keycloak_authentication_flow.custom_reset_credentials_flow.alias
  authenticator     = "reset-credential-email"
  requirement       = "REQUIRED"
  priority          = 20
  depends_on        = [
    keycloak_authentication_execution.limit-resend-email-authenticator
  ]
}

# Step 3: Reset Password
resource "keycloak_authentication_execution" "reset-password" {
  realm_id          = keycloak_realm.realm.id
  parent_flow_alias = keycloak_authentication_flow.custom_reset_credentials_flow.alias
  authenticator     = "reset-password"
  requirement       = "REQUIRED"
  priority          = 30
  depends_on        = [keycloak_authentication_execution.reset-credential-email]
}

resource "keycloak_authentication_subflow" "reset_otp_subflow" {
  realm_id          = keycloak_realm.realm.id
  alias             = "reset-conditional-otp-subflow"
  parent_flow_alias = keycloak_authentication_flow.custom_reset_credentials_flow.alias
  provider_id       = "basic-flow"
  requirement       = "CONDITIONAL"
  priority          = 40
}

# Step 4.1: Condition – User Configured
resource "keycloak_authentication_execution" "condition_user_configured" {
  realm_id          = keycloak_realm.realm.id
  parent_flow_alias = keycloak_authentication_subflow.reset_otp_subflow.alias
  authenticator     = "conditional-user-configured"
  requirement       = "REQUIRED"
  depends_on        = [keycloak_authentication_subflow.reset_otp_subflow]
}

# Step 4.2: Reset OTP
resource "keycloak_authentication_execution" "reset-otp" {
  realm_id          = keycloak_realm.realm.id
  parent_flow_alias = keycloak_authentication_subflow.reset_otp_subflow.alias
  authenticator     = "reset-otp"
  requirement       = "REQUIRED"
  depends_on        = [keycloak_authentication_execution.condition_user_configured]
}

# Bind your custom flow
resource "keycloak_authentication_bindings" "reset_credentials_flow_binding" {
  realm_id               = keycloak_realm.realm.id
  reset_credentials_flow = keycloak_authentication_flow.custom_reset_credentials_flow.alias

  depends_on = [keycloak_authentication_execution.reset-otp]
}
```

</details>

#### 6.4  Go to "Authentication" → "Required Actions" tab → Make sure **Verify Email** is enabled and the **Default Action** is set to **On**.

<details>
<summary>or configure 6.4 section using terraform</summary>

[tested using keycloak Provider 5.2.0](https://registry.terraform.io/providers/keycloak/keycloak/5.2.0/docs)

```terraform
resource "keycloak_required_action" "verify_email" {
  realm_id       = keycloak_realm.realm.id
  alias          = "VERIFY_EMAIL"
  name           = "Verify Email"
  enabled        = true
  default_action = true
  priority       = 50
}
```

</details>

### 7. Optionally show user attributes in keycloak to see LimitResendEmailCount for each user
- Realm settings
  - General
    - Unmanaged Attributes
      - set `Only administrators can view`

<details>

<summary>or configure 7 section using terraform</summary>

[tested using keycloak Provider 5.2.0](https://registry.terraform.io/providers/keycloak/keycloak/5.2.0/docs)

```terraform
resource "keycloak_realm_user_profile" "userprofile" {
  realm_id                   = keycloak_realm.realm.id
  unmanaged_attribute_policy = "ADMIN_VIEW"
  ...
}
```
</details>

## ✨ Features

## Feature #1 (Forgot password protection):
- **(Problem we solve):** The user can abuse the system by repeatedly triggering "Forgot password", spamming password reset emails.
- After user registration, the Email Verification page is shown with a "Resend" link.  
  The user clicks "Resend" more than 3 times but does not open or confirm any of the emails.
- **User then opens the Login page and clicks "Forgot password" again and again which leads to a spam**
- **(Solution):** If more than 3 verification or forgot password emails were sent within the last hour and none were confirmed, an error is shown to temporarily block further emails.  

## Feature #2 (Login protection):
**(Problem we solve):** A user can repeatedly log in to trigger email-verification emails without confirming any, leading to spam.
- After registration, the user is shown the Email Verification page with a "Resend" link.  
  The user clicks "Resend" more than 3 times but does not confirm any of the emails.
- **User then opens the Login page and enters the correct username and password.**
- Keycloak redirects to the Verification page and sends a new email verification email.
- **(Solution):** If the limit is reached, the Verification page shows an error and no email is sent during 1 hour.
[![Watch the video](https://img.youtube.com/vi/0jJc2Xn8FO0/maxresdefault.jpg)](https://youtu.be/0jJc2Xn8FO0)
Click to Watch demo video ^

## Feature #3 (Email verification page protection)
### ❕️ Note:
Starting from 26.4.0 Keycloak has a built-in feature [A configurable cooldown for email resend in VerifyEmail](https://github.com/keycloak/keycloak/commit/6958f57f0af7aac4213a5a8c2ed281542ea61478)
> When validating an email address as a required action or an application initiated action, a user can resend the verification email by default only every 30 seconds, while in earlier versions there was no limitation in re-sending the email.  
> Administrators can configure the interval per realm in the Verify Email required action in the Authentication section of the realmm.
### but the cooldown doesn't limit the number of resends, and it does not block after limit is reached
- **(Problem we solve):** The user can trigger excessive email-verification messages by repeatedly clicking "Resend" after each cooldown seconds.
- After registration, the user is shown the Email Verification page with a "Resend" link.  
  The user clicks "Resend" more than 3 times without confirming any emails.
- **(Solution):** If the resend limit is reached, the page shows an error and no email is sent during 1 hour.
[![Watch the video](https://img.youtube.com/vi/QoAOQE7uvGE/maxresdefault.jpg)](https://youtu.be/QoAOQE7uvGE)
Click to Watch demo video ^
> If all email verification links have expired (increase recommended to 30 minutes instead of default 5) and the user forgot password then user selects "Forgot Password," and request a reset password email after 1 hour.
> Resetting the password does not automatically verify the email. After logging in with the new password, the user will still see the email verification page, and a new verification email will be sent.

---

## 🎨 Fork additions — template (FTL) integration

This fork ([ADarkDividedGem/keycloak-spi-limit-resend-email](https://github.com/ADarkDividedGem/keycloak-spi-limit-resend-email)) adds two enhancements on top of upstream:

1. **Per-realm configuration overrides** — see [Overriding per realm without restarting Keycloak](#overriding-per-realm-without-restarting-keycloak) above.
2. **Retries-left and block-countdown exposed to the login theme** — so that custom FreeMarker (`.ftl`) templates can show the user *how many attempts they have left* and *how long until the block is lifted*, instead of only a generic error.

### Form attributes available in `.ftl` templates

When the SPI renders the verify-email page or the "too many requests" error page, it sets the following attributes on the Keycloak `LoginFormsProvider`. They are accessible in any custom theme's `.ftl` templates (e.g. `login-verify-email.ftl`, `error.ftl`).

| Attribute                | Type    | Where set                                                                                                  | Meaning                                                                                                   |
|--------------------------|---------|------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------|
| `retriesLeft`            | `int`   | Verify-email page **and** the 429 error page (authenticator + required action)                             | Resend attempts remaining before the block kicks in. On the blocked page this is `0`.                     |
| `secondsUntilUnblocked`  | `int`   | 429 error page (when blocked) **and** the verify-email page when the final retry has just been exhausted   | Raw seconds remaining until the user can trigger another email.                                           |
| `minutesUntilUnblocked`  | `int`   | 429 error page (when blocked) **and** the verify-email page when the final retry has just been exhausted   | `secondsUntilUnblocked` rounded **up** to the nearest minute — convenient for user-facing messages.       |

### Localized message key

A new message key is registered by the SPI via `theme-resources/messages/messages_en.properties`:

| Key                                      | Default English value                                                            |
|------------------------------------------|----------------------------------------------------------------------------------|
| `limitResendEmailTooManyRequestsError`   | `Too many email attempts. Please wait {0} minute(s) before trying again.`        |

The `{0}` placeholder is substituted with `minutesUntilUnblocked`. Custom themes can override this key in their own `messages_<locale>.properties` to translate or reword the message.

### Example FTL usage

Showing the remaining attempts on the verify-email page (e.g. in `login-verify-email.ftl`):

```ftl
<#if retriesLeft?? && (retriesLeft > 0)>
  <p class="instruction">
    You have <strong>${retriesLeft}</strong> resend attempt(s) remaining
    before sending is temporarily blocked.
  </p>
</#if>
```

Showing a friendly countdown on the blocked error page (e.g. in `error.ftl`):

```ftl
<#if minutesUntilUnblocked??>
  <p>
    Please try again in approximately
    <strong>${minutesUntilUnblocked}</strong> minute(s).
  </p>
</#if>
```

Because `retriesLeft` is set on the verify-email page too, you can optionally degrade the message as the user approaches the limit:

```ftl
<#if retriesLeft?? && (retriesLeft <= 1)>
  <p class="kc-feedback-text warning">
    This is your last resend attempt before emails are blocked for a period of time.
  </p>
</#if>
```

When the user has just exhausted their final retry, the verify-email page also exposes `secondsUntilUnblocked` / `minutesUntilUnblocked`, so the template can hide the resend link and render a countdown instead:

```ftl
<#if retriesLeft?? && retriesLeft == 0 && secondsUntilUnblocked??>
  <p class="instruction" data-seconds-until-unblocked="${secondsUntilUnblocked}">
    Please wait <strong>${minutesUntilUnblocked}</strong> minute(s)
    before requesting another email.
  </p>
<#else>
  <!-- existing resend link -->
</#if>
```

A bit of JavaScript can then tick `data-seconds-until-unblocked` down to zero and re-enable the resend link client-side.

> ℹ️ The SPI uses `setError("limitResendEmailTooManyRequestsError", minutesUntilUnblocked)` on the blocked page, so the default Keycloak `${message.summary}` rendering in `error.ftl` will already show the localized text — you only need to reach for the raw attributes above if you want richer, custom markup.

---

# Keycloak Custom SPI Extensions

This repository contains custom [Keycloak](https://www.keycloak.org/) Service Provider Interfaces (SPI) for:
- Custom **Authenticator** for blocking sending emails after many Forgot Password clicks 
- Custom **EventListener** for counting how many times user clicked Forgot Password or Resend verification email 
- Custom **VerifyEmail** that overrides "existing VerifyEmail Required Action" for blocking sending emails after many resend clicks or many logins with unverified email

These extensions are designed to enhance the login flow and event tracking features of Keycloak.

---

## Development Notes
Destroy local keycloak
```bash
docker compose rm -f -s -v keycloak
```
Spin up local keycloak
```bash
 docker compose up
```
Build the JAR
```bash
mvn clean package
mvn clean package -DskipTests
```
Ensure JAR includes necessary META-INF services
```bash
jar tf target/idnord.keycloak-*.jar | grep META-INF/services/
```
Expected services:  
(IMPORTANT: if you see more than this list of services in jar it means that a dependency in pom.xml needs scope `<scope>provided</scope>`)
```text
META-INF/services/
META-INF/services/org.keycloak.authentication.AuthenticatorFactory
META-INF/services/org.keycloak.authentication.RequiredActionFactory
META-INF/services/org.keycloak.events.EventListenerProviderFactory
```
Upload spi into local keycloak, build and restart
```bash
./install.sh
```

To see attributes of a user in keycloak 
- Realm settings
  - General
    - Unmanaged Attributes
      - set `Only administrators can view`

---

# License
MIT
