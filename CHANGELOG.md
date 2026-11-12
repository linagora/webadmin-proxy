# Changelog
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)

## [Unreleased]

### Upgrade instructions

- If a client's `allowed.urls` is only an include of a file holding no rule, the proxy now refuses to start (it used to allow every call). To grant nothing, write the explicit deny-all rule `{"denied": true, "endpoint": "/*"}`. Omitting `allowed.urls` still leaves a client unrestricted.
- If an endpoint pattern lists the same query parameter twice (for example `?user=%@{domain}&user={domain}`), the proxy now refuses to start (#43). List each parameter once.
- Request bodies are now read after authorization and are limited. Set `request.body.max.size` (100M by default, 413 above) and the new `request.read.timeout` and `request.timeout` (408 when a body does not arrive) if the defaults do not suit you.
- In the mail baseline, writing a domain alias now belongs to platform operators, and a user alias needs its target and source in the same domain. Domain administrators may only rename a user into their own domain. Review profiles that relied on the previous behaviour.
- In the calendar baseline, the address book copy is now denied whenever `sourceDomain` is present, whatever the spelling of the action.

### Changed

- A rule listing `GET` now also covers `HEAD`, so a deny rule on `GET` no longer lets `HEAD` through (#42).
- A query parameter named by a rule must now appear only once for an allow rule to match, and a deny rule matches when it is repeated. Parameters that no rule names may still repeat.
- Form bodies are refused with a 415, as no proxied route takes a form.
- `/.proxy/myDomain` answers 403 when the user is not an address with a single `@` and a non-blank domain (#34, #38).
- A blank value of `oidc.claim.authenticated.user` is answered with 401, like a missing claim, instead of forwarding the request with an empty user (#35, #36).
- Backchannel logout answers 400 when the `sid` of the logout token is not a non-empty JSON string (#32, #39).

### Fixed

- Request paths are now decoded per RFC 3986: a `+` in a path variable is kept as is, so plus-addressed users are no longer refused under `EQUALS` restrictions (#31, #41).
- A query with a malformed percent escape (such as `?x=%`) is refused with 400 instead of a 500 (#27, #45).
- `*` and valueless flags in rules now match line terminators, so a target ending with an encoded line break can no longer skip a deny rule.
- Sessions are no longer evicted by a logout whose `sid` is a number, and a non-string `sid` returned by introspection or userinfo is no longer registered (#32, #39).
- `HAS_DOMAIN` refuses a claim that is not a single-`@` address with 403 instead of granting a wrong domain (#33).
- OIDC userinfo claims, audiences and `client_id` are only accepted when they are JSON strings: a `null`, a number or a boolean can no longer satisfy a restriction, an expected claim, an audience, a client or `authorized.users` (#46).

### Security

- Requests carrying a raw fragment (`#`), a non-canonical path (empty or dot segments, including encoded ones such as `%2F..%2F`) or a target that is not in origin-form are refused, so the backend can no longer receive a different target than the one access was checked on.
- `X-HTTP-Method-Override` is no longer forwarded to the backend.
- A domain administrator can no longer take over an account of another domain through a rename, and alias writes now bind both domains involved.
- An allow-all profile can no longer result from an include holding no rule.
