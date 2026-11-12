# Changelog
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)

## [Unreleased]

## [1.2.0] - 2026-11-12

### Upgrade instructions

- Request bodies are now read after authorization and are limited: `request.body.max.size` (100M by default; larger bodies get a 413), plus the new `request.read.timeout` and `request.timeout` settings (408 when a body does not arrive in time). Adjust them if your clients send large bodies or are slow.
- An `allowed.urls` that is only an include of a file holding no rule is now refused at startup. To grant nothing, write the explicit deny-all rule `{"denied": true, "endpoint": "/*"}`. Omitting `allowed.urls` still leaves a client unrestricted.
- An endpoint pattern listing the same query parameter twice (e.g. `?user=%@{domain}&user={domain}`) is now a startup error: fix such patterns.
- Alias writes in the mail baseline are stricter: writing a domain alias now belongs to platform operators, and a user alias needs its target and source in the same domain.
- In the mail baseline, a domain administrator may only rename a user into their own domain. Any other rename is denied.
- In the calendar baseline, the address book copy rule now denies the presence of `sourceDomain` whatever the spelling of the action.

### Added

- One instance can now serve several backends and OIDC providers: a client entry may set `path.prefix` and `oidc.provider`, and `oidc.providers` holds named providers. The top-level `oidc.*` keys define the provider named `default`, so existing configurations keep working unchanged.

### Changed

- A query parameter named by a rule must now appear only once for an allow rule to match, and a deny rule treats a repeated parameter as matching. Parameters no rule names may still repeat.
- A rule listing `GET` now also covers `HEAD` (#42).
- `*` and valueless flags in URL rules now match line terminators too.
- A blank value of the authenticated user claim (`oidc.claim.authenticated.user`) is refused with 401, like a missing claim (#36).
- `/.proxy/myDomain` and `HAS_DOMAIN` only accept a user or claim that is an address with a single `@` and a non-blank domain part; otherwise they answer 403 (#38).
- Form bodies are refused with a 415, as no proxied route takes a form.
- Only JSON strings are accepted as OIDC claim values, audiences and `client_id`; a `null`, number or boolean is no longer read as text (#46).

### Fixed

- Request paths are decoded per RFC 3986: a `+` in a path is kept literally, so plus-addressed users are no longer refused under `EQUALS` restrictions (#41).
- A request whose query carries a malformed percent escape (`?x=%`) is refused with 400 instead of answering 500 (#45).
- Backchannel logout only accepts a `sid` that is a non-empty JSON string, and a non-string `sid` from introspection or userinfo is ignored; `{"sid":1}` no longer evicts the session `"1"` (#39).

### Security

- A domain administrator can no longer rename a user into another tenant's account, which could take over its delegations and forwards.
- Requests are refused when their target is not in origin-form, carries a raw `#` fragment, or has a non-canonical path (empty or dot segments, including those hidden behind `%2F`), so that rules and backends see the same path.
- `X-HTTP-Method-Override` is no longer forwarded to the backend.
- A deny rule can no longer be skipped by a target containing a line terminator (e.g. `%0A`).

## [1.1.8] - 2026-10-22

### Upgrade instructions

- The Docker image is now built from a Dockerfile and runs as a non-root user instead of root. If you mount volumes or files into the container, make sure they are readable (and writable where needed) by that user.

### Changed

- The Docker image is no longer built with jib: it is built from a Dockerfile and the process runs as a non-root user.

## [1.1.7] - 2026-10-13

### Added

- The CORS configuration now exposes the `X-Group` and `X-Insertion-Id` response headers (`Access-Control-Expose-Headers`), so the admin console can read dead-letter event locations from cross-origin requests (#19, #20)

### Changed

- The Docker image is now based on `eclipse-temurin` 27 (`resolute`) (#21, #22)
- The LDAP REST profiles have been refined (#23)

## [1.1.6] - 2026-10-09

### Changed

- The calendar baseline now forbids copying an address book across domains: `POST /domains/{domain}/addressbooks/{id}?action=copyFrom` is allowed only when `sourceDomain` equals the target domain, and any other `copyFrom` is denied (#16)
- Added LDAP-rest compatible variants of the functional baseline and admin mail profiles. They deny user quotas, user aliases, user renames and mailing list writes, which ldap-rest's Twake James plugin now owns, while forwards and identities remain manageable. The denials live in a shared `ldap-rest-managed-denials.json` fragment included first (#15)

## [1.1.5] - 2026-09-23

### Changed

- The OIDC token cache no longer logs a message for each token: a missing `sid` claim is now warned about once, then logged at DEBUG level, and no token material ends up in the logs (#14, #13)

## [1.1.4] - 2026-09-17

### Added

- More profiles are available.

### Changed

- A 401 from the James backend is now logged as an error and answered with a 500 and an explicit message that `webadmin.token` is misconfigured. Before, it was forwarded as is, and frontends believed their own OIDC session had expired. The backend headers, notably `WWW-Authenticate`, are no longer passed on (#11, #10)
- Rule matching has been adapted.
- A rule whose verbs are an empty array is now rejected.

## [1.1.3] - 2026-08-24

### Added

- Calendar support profiles now cover mailing lists, team calendars, address books and booking links (#8)
- The profile editor is now linked from the interface

### Changed

- Improved rule matching

## [1.1.2] - 2026-07-09

### Fixed

- Browsers can now send the `I-KNOW-WHAT-I-M-DOING` header through the proxy: it is advertised in the CORS preflight response (`Access-Control-Allow-Headers`), so destructive WebAdmin endpoints such as `POST /mailboxes?task=SolveInconsistencies` no longer fail from a web client (#6, closes #5)

## [1.1.1] - 2026-06-25

### Fixed

- The `linagora-mail-support-profile.json` profile now grants the support team access to root-level listing endpoints (such as `GET /domains`), so the frontend sidebar shows all its sections instead of only healthcheck, metrics, tasks and common-tasks (#4)

## [1.1.0] - 2026-06-25

### Added

- Blind proxy for the WebAdmin API: requests are forwarded to the backend WebAdmin server.
- OIDC authentication: the proxy validates the access tokens of incoming requests against the OpenID Connect provider.
- Extra claim validation: additional claims of the token can be required, and an optional scope validation is available.
- Restricted URLs: each client is limited to the URLs it is allowed to call, and denied URLs can be declared.
- Domain validation: users and domains a client may act on are checked, hardened against bypasses, with wildcards supported on the local part of email addresses in user endpoints.
- Authorized users: access can be restricted to a given list of users.
- Backchannel logout: sessions are ended when the OIDC provider sends a logout notification.
- Minimal observability: the proxy exposes basic health and metrics information.
- The configuration is advertised to clients, and self-service info endpoints let a user query their own access through the proxy.
- Multi-valued audiences are accepted in tokens, and `sid` is also supported in the userinfo response.
- Audit logging: the user and the client id are recorded with every log entry, and Logback JSON logging is bundled.
- Several access profiles (`allowed.urls`) can be set on a single client id (ISSUE-1231).
- Access profiles can include other profiles, including recursively, and a convenience builder for client configuration is available.
- Linagora access profiles (admin and support) are provided in the classpath, and the default templates are polished.
- Project documentation.

### Changed

- The project is built with JDK 25.
- Existing access profiles are updated.

### Fixed

- CORS handling is corrected.
- Users given in query parameters are now supported.
- Support profiles now allow the `/tasks` endpoint, so the task list is reachable (#2).
