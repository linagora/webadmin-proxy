# Configuration reference

Configuration is loaded from a single JSON file (`configuration.json`). Any value may reference an environment variable using the `{ENV:VAR_NAME}` syntax — the proxy will fail to start if a referenced variable is absent.

## Full example

```json
{
  "port": "8001",
  "oidc.userInfo.url": "http://lemonldap:19090/oauth2/userinfo",
  "oidc.introspect.url": "http://lemonldap:19090/oauth2/introspect",
  "oidc.introspect.credentials": "Bearer {ENV:OIDC_INTROSPECT_CREDENTIALS}",
  "oidc.audience": "webadmin-proxy",
  "oidc.claim.authenticated.user": "email",
  "oidc.token.cache.expiration": "60s",
  "cors.allow.origin": ["https://twake-mail-admin.linagora.com", "https://twake-calendar-admin.linagora.com"],
  "self.webadmin.enabled": "true",
  "self.webadmin.port": "8002",
  "clients": {
    "twakemail-client": {
      "webadmin.backend": "http://james:8000",
      "webadmin.token": "{ENV:TWAKEMAIL_WEBADMIN_TOKEN}",
      "expected.claims": {
        "admin": "1"
      },
      "authorized.users": ["alice@example.com", "bob@example.com"],
      "allowed.urls": [
        {"verb": ["GET"], "endpoint": "/domains/{domain}/users"},
        {"endpoint": "/domains/{domain}/aliases/*"}
      ],
      "url.patterns.restrictions": {
        "domain": {
          "backing.claim": "domain",
          "operator": "EQUALS"
        }
      }
    }
  }
}
```

## Top-level fields

| Field | Required | Description |
|-------|----------|-------------|
| `port` | yes | Port the proxy listens on |
| `oidc.userInfo.url` | yes | OIDC userinfo endpoint URL |
| `oidc.introspect.url` | yes | OIDC token introspection endpoint URL |
| `oidc.introspect.credentials` | no | Credentials sent with introspect requests (e.g. `Bearer <token>`) |
| `oidc.audience` | yes | Expected value of the `aud` claim. Tokens with a different audience are rejected with 401 |
| `oidc.claim.authenticated.user` | yes | Name of the userinfo claim used as the authenticated user identity (typically `email`) |
| `oidc.token.cache.expiration` | yes | How long resolved tokens are cached. Format: `<n>s`, `<n>m`, etc. |
| `cors.allow.origin` | no | Allowed CORS origin(s). Accepts a single string or a JSON array. Use `"*"` to allow all origins, or list specific origins (e.g. `["https://app.example.com", "https://admin.example.com"]`). Absent = no CORS headers added |
| `self.webadmin.enabled` | no | `true` to start the self-admin HTTP server. Defaults to `false` |
| `self.webadmin.port` | no | Port for the self-admin server. Required when `self.webadmin.enabled` is `true`. Use `0` for a random port |
| `clients` | yes | Map of client configurations, keyed by OIDC `client_id` |

## Client configuration

Each entry under `clients` is keyed by the OIDC `client_id` value that clients present in their tokens.

| Field | Required | Description |
|-------|----------|-------------|
| `webadmin.backend` | yes | Base URL of the James WebAdmin backend to proxy to |
| `webadmin.token` | yes | Bearer token used to authenticate requests to the backend |
| `expected.claims` | no | Map of claim name → required value. All listed claims must be present in userinfo with exactly the specified value |
| `authorized.users` | no | Allowlist of user identities (as resolved by `oidc.claim.authenticated.user`). If non-empty, only listed users are admitted. Useful when OIDC claim configuration is impractical |
| `allowed.urls` | no | List of allowed endpoint rules. If omitted or empty, all URLs are allowed |
| `url.patterns.restrictions` | no | Constraints on URL template variables, validated against OIDC claims |

### allowed.urls rules

Each rule has:

| Field | Required | Description |
|-------|----------|-------------|
| `endpoint` | yes | Endpoint pattern (see pattern syntax below) |
| `verb` | no | List of allowed HTTP verbs (e.g. `["GET", "PUT"]`). If omitted, all verbs are allowed |

### Endpoint pattern syntax

| Token | Meaning |
|-------|---------|
| `{varname}` | Captures exactly one path segment (no `/`). The captured value is available to `url.patterns.restrictions` |
| `%` | Matches the local part of an email address (no `@`, no `/`). Pure consumer — no named capture |
| `*` | Matches any remaining characters, including `/` |
| `?param={varname}` | Captures a query parameter value into a named variable |
| `?param=value` | Requires query parameter to equal a literal value |

Extra query parameters in the request are ignored. All listed query parameters must be present.

Examples:
- `/users` — exact path
- `/domains/{domain}/users` — one variable segment
- `/users/%@{domain}/mailboxes` — email address user, domain captured
- `/domains/{domain}/aliases/*` — any sub-path under aliases
- `/quota?scope={scope}` — captures a query parameter

### url.patterns.restrictions

Constraints on captured URL variables. Each entry is keyed by the variable name as it appears in the endpoint pattern.

| Field | Required | Description |
|-------|----------|-------------|
| `backing.claim` | yes | Name of the OIDC claim whose value is used for comparison. If the claim is absent from the token, the request is rejected with 403 |
| `operator` | yes | How to compare the claim value against the URL variable. See operators below |

Operators:

| Operator | Description |
|----------|-------------|
| `EQUALS` | The claim value must exactly equal the URL variable value |
| `HAS_DOMAIN` | The claim value is parsed as an email address; its domain part must equal the URL variable value. Useful with the `email` claim |

## Self-admin API

When `self.webadmin.enabled: true`, the following endpoints are available on `self.webadmin.port`:

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/backchannel-logout` | `POST` | Receives an OIDC backchannel logout notification (`application/x-www-form-urlencoded` with `logout_token`). Extracts the `sid` claim and invalidates all matching cache entries |
| `/metrics` | `GET` | Prometheus text-format metrics |
| `/healthcheck` | `GET` | Returns 200 when the server has fully started |
| `/healthcheck/checks` | `GET` | Lists individual health checks |

## Proxy self-service endpoints

Available on the main proxy port, authenticated via OIDC. All endpoints require a valid Bearer token and apply the same `authorized.users` check as regular requests. They are never forwarded to the James backend.

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/.proxy/allowed/urls` | `GET` | Returns the `allowed.urls` list for the caller's client as JSON. Returns 204 if not configured. Intended for frontends that adapt their UI based on permission level |
| `/.proxy/whoami` | `GET` | Returns the authenticated user identity: `{"email":"user@example.com"}` |
| `/.proxy/myDomain` | `GET` | Returns the caller's domain: `{"domain":"example.com"}`. Resolved from the `domain` claim in userinfo if present, otherwise extracted from the domain part of the email claim |
