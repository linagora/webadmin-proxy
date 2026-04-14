# Architecture

## Overview

```
                        ┌─────────────────────────────────────────┐
                        │            WebAdmin Proxy                │
                        │                                          │
  Admin client          │  ┌────────────┐    ┌──────────────────┐ │
  Bearer token  ──────► │  │  OIDC auth │───►│  Access control  │ │──► Webadmin A: Twake Mail
                        │  └────────────┘    └──────────────────┘ │
                        │         │                                │──► Webadmin B: Twake Calendar
                        │         ▼                                │
                        │  ┌────────────┐                          │
  OIDC provider ◄───────│  │Token cache │                          │
  (LemonLDAP)           │  └────────────┘                          │
                        └─────────────────────────────────────────┘
                                       │ admin port
                               ┌───────┴──────────────┐
                               │  Self-admin API       │
                               │  /backchannel-logout  │
                               │  /metrics             │
                               │  /healthcheck         │
                               └───────────────────────┘
```

## Request flow

For every incoming request:

1. **Extract token** — the `Authorization: Bearer <token>` header is required. Absence → 401.
2. **Resolve token** (cached) — the proxy calls the OIDC introspect and userinfo endpoints in parallel. Results are cached in Caffeine with a configurable TTL to reduce pressure on the OIDC provider.
3. **Validate audience** — the `aud` claim in the introspect response must equal `oidc.audience`. Mismatch → 401.
4. **Select client config** — the `client_id` from the introspect response is looked up in the `clients` map. Unknown client → 401.
5. **Check extra claims** — if `expected.claims` is set for the client, each required claim/value pair is verified against the userinfo response. Mismatch → 403.
6. **Check authorized users** — if `authorized.users` is set, the resolved user (from the configured claim) must appear in the list. Absent → 403.
7. **Check URL allowlist** — if `allowed.urls` is set, the request method and path are matched against the patterns. No match → 403.
8. **Check variable restrictions** — captured URL template variables are compared against OIDC claim values according to `url.patterns.restrictions`. Mismatch → 403.
9. **Proxy to backend** — the request is forwarded to the client's `webadmin.backend`, with the `Authorization` header replaced by `webadmin.token`. All other headers and the body are forwarded as-is. The backend response is returned verbatim.

## Special proxy endpoints

Requests matching `GET /.proxy/allowed/urls` are handled by the proxy itself and never forwarded to James. The proxy authenticates the caller (steps 1–6), then returns the `allowed.urls` list for that client as JSON. This allows frontends to adapt their UI based on the caller's permission level.

## Components

| Class | Role |
|-------|------|
| `WebAdminProxy` | reactor-netty server, top-level request routing |
| `OidcTokenResolver` | calls introspect + userinfo, assembles `AuthenticatedRequest` |
| `CaffeineOidcTokenCache` | caches resolved tokens; supports SID-based invalidation |
| `AllowedUrlsHandler` | serves `GET /.proxy/allowed/urls` |
| `AllowedUrl` | pattern matching for URL allowlist rules |
| `WebAdminProxyConfiguration` | parsed configuration, immutable record |
| `WebAdminProxyModule` | Guice bindings |
| `SelfAdminModule` | Guice bindings for the self-admin Spark/Jetty server |
| `BackchannelLogoutRoutes` | handles `POST /backchannel-logout` on the admin port |

## Token cache and backchannel logout

Resolved tokens are cached by their raw value. The cache entry stores the full `AuthenticatedRequest` (user, client ID, claims). Expiry is controlled by `oidc.token.cache.expiration`.

When the OIDC provider terminates a session it may call `POST /backchannel-logout` on the proxy's admin port with a signed logout JWT. The proxy extracts the `sid` claim from the JWT payload and evicts all cache entries sharing that session ID.

## Observability

When `self.webadmin.enabled: true`, the proxy starts a Spark/Jetty server on `self.webadmin.port` exposing:

- `GET /metrics` — Prometheus text format via Dropwizard `MetricRegistry`
- `GET /healthcheck` — James standard health-check API
- `GET /healthcheck/checks` — lists individual checks (includes `Guice application lifecycle`)

The proxy records one metric: `webadmin.proxy.requests` (counter incremented on every successfully authenticated and dispatched request).
