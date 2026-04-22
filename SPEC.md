# WebAdmin proxy

## Why ?

Apache James webadmin do not support avanced auth, only static conf tokens, it includes some unauthenticated technical endpoints and thus cannot be opened on the internet. 
It also do not support access control as it do not have a concept of users

We wishes to write a proxy (this project!) to:
 - Authenticate administrators with OIDC
 - Validate their permissions (ClientID based) 
 - Proxy calls onto an actual webadmin

## Tools

Maven: /opt/apache-maven-3.9.6/bin/mvn

Do not commit: this is a human privilege.

## How ?

Here is a serie of steps. Be autonomous at each step, but ask me for validation between each step.

### Step 1: Blind proxy

More complex logic will follow timely in follow up prompts !

#### Guidelines

 - Use a single maven project structure!
 - Use Apache james as a BOM

```

            <dependency>
                <groupId>${james.groupId}</groupId>
                <artifactId>james-project</artifactId>
                <version>${james.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            <dependency>
                <groupId>${james.groupId}</groupId>
                <artifactId>james-server-guice</artifactId>
                <version>${james.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
```

 - Set up application with Guice using the the pattern than calendar app with your own modules 
   CF /home/hp/Documents/twake-calendar/app/src/main/java/com/linagora/calendar/app/TwakeCalendarCommonServicesModule.java
   CF /home/hp/Documents/twake-calendar/app/src/main/java/com/linagora/calendar/app/TwakeCalendarConfiguration.java
   CF /home/hp/Documents/twake-calendar/app/src/main/java/com/linagora/calendar/app/TwakeCalendarGuiceServer.java
   CF /home/hp/Documents/twake-calendar/app/src/main/java/com/linagora/calendar/app/TwakeCalendarMain.java
   Be minimal in the modules you import

 - Use reactor-netty to write the proxy.
    - Don't implement complex routing. Just wire directly a server 
    - An exemple of such a working proxy (backend) can be found in /home/hp/Documents/twake-calendar/calendar-rest-api/src/main/java/com/linagora/calendar/restapi/DavProxy.java
    - Improve the code to forward all headers from and to webadmin

We do not write OIDC stuff now, our first delivery is JUST a as-is proxy

Only config is `configuration.json`:

```
{
  "port": "8001",
  "webadmin.backend": "http://127.0.0.1:8000"
}
```

Note: `webadmin.backend` will be moved into each client entry in Step 2, to allow a single proxy instance to front multiple backends.

Configuration is to be represented by a POJO and shall thus be injected. No direct configuration lookup in the code!
Configuration must interpolate env vars:

```
{
  "port": "{ENV:PORT}",
  "webadmin.backend": "http://127.0.0.1:8000"
}
```

LOG configuration POJO upon start to allow easy validation that parsing is right.

Or something similar. Choose a classic configuration library and do not reinvent the wheel... 

Write tests with MockHttpServer ensuring we actually forward correctly requests. This will write IT tests will start our proxy, start a mock server, and ensure we forward standard requests (GET, PUT, POST, etc..) and headers.

Do a JIB packaging CF /home/hp/Documents/twake-calendar/app/pom.xml

Setup logging with logback.

### Step 2: OIDC auth for our proxy

The idea is to leverage the clientid in the OIDC token in order to assert the access level of the user, validate the actions performed and map it to a backend.

At this step we will NOT implement any validation, we would JUST write the:
 - OIDC auth frontend
 - Have the backend substiture the webdmin token in the backend

#### Targeted configuration

Only config is `configuration.json`:

Note: `oidc.introspect.credentials` is optional.

Note: `webadmin.backend` is configured per client, allowing a single proxy instance to front multiple backends (e.g. both Twake Mail and Twake Calendar webadmin).

```json
{
  "port": "8001",
  "oidc.userInfo.url": "http://keycloak:8080/auth/realms/oidc/protocol/openid-connect/token/userinfo",
  "oidc.introspect.url": "http://keycloak:8080/auth/realms/oidc/protocol/openid-connect/token/introspect",
  "oidc.introspect.credentials": "Bearer ewjiwelhwew",
  "oidc.audience": "webadmin-proxy",
  "oidc.claim.authenticated.user": "email",
  "oidc.token.cache.expiration": "360s",
  "clients": {
    "clientIdA": {
      "webadmin.backend": "http://127.0.0.1:8000",
      "webadmin.token": "mastertokenvalue"
    },
    "clientIdB": {
      "webadmin.backend": "http://127.0.0.1:8001",
      "webadmin.token": "readonlytoken"
    }
  }
}
```

#### Guidelines

Duplicate Twake calendar OIDC stack and adapt it to our needs...

CF /home/hp/Documents/twake-calendar/calendar-rest-api/src/main/java/com/linagora/calendar/restapi/auth/OidcAuthenticationStrategy.java
CF /home/hp/Documents/twake-calendar/calendar-rest-api/src/main/java/com/linagora/calendar/restapi/auth/OidcEndpointsInfoResolver.java

The OIDC stack resolves aggressively userinfo & introspect in order to handle also OPAQUE tokens. Do the same. We have /home/hp/Documents/twake-calendar/storage-api/src/main/java/com/linagora/calendar/storage/OIDCTokenCache.java
to cache things a bit and lower the pressure on the provider. /home/hp/Documents/twake-calendar/storage-api/src/main/java/com/linagora/calendar/storage/CaffeineOIDCTokenCache.java .
We do NOT implement the redis token cache as this service will be single instance, we keep Caffeine token cache for simplicity.

(We do not need MailboxSession and SimpleSessionProvider, use simpler abstraction.)

We need to cary other:
 - authenticated user (obtained through configured claim with userinfo)
 - client id (client_id obtained from the introspect response, per RFC 7662)

LOG (INFO) which enpoint is called by who. Use MDC (action: GET / POST, endpoint, clientId, user, ...)

The `oidc.audience` value MUST be validated as part of OIDC token validation: the audience claim in the resolved token MUST match the configured value. Requests whose token carries a non-matching or missing audience MUST be rejected with 401.

Use the configured webadmin token as an authentication backend

Validate with IT tests, including edge cases.

#### Definition of done

Definition of done: Write IT tests.

### Step 3: Extra claim validation

Sample conf:

Note: `oidc.introspect.credentials` is optional.

```json
{
  "port": "8001",
  "oidc.userInfo.url": "http://keycloak:8080/auth/realms/oidc/protocol/openid-connect/token/userinfo",
  "oidc.introspect.url": "http://keycloak:8080/auth/realms/oidc/protocol/openid-connect/token/introspect",
  "oidc.introspect.credentials": "Bearer ewjiwelhwew",
  "oidc.audience": "webadmin-proxy",
  "oidc.claim.authenticated.user": "email",
  "oidc.token.cache.expiration": "360s",
  "clients": {
    "clientIdA": {
      "webadmin.backend": "http://127.0.0.1:8000",
      "webadmin.token": "mastertokenvalue",
      "expected.claims": {
        "admin": "1"
      }
    },
    "clientIdB": {
      "webadmin.backend": "http://127.0.0.1:8001",
      "webadmin.token": "readonlytoken"
    }
  }
}
```

People connecting from clientIdA will need to have admin claim set to 1 in order to use the service.

Definition of done: Write IT tests.

### Step 4: URL restrictions

We want to restrict URLs that can be used by some clients. We shall support mathing url templates in endpoints to allow complex use cases :

Note: `oidc.introspect.credentials` is optional.

```json
{
  "port": "8001",
  "oidc.userInfo.url": "http://keycloak:8080/auth/realms/oidc/protocol/openid-connect/token/userinfo",
  "oidc.introspect.url": "http://keycloak:8080/auth/realms/oidc/protocol/openid-connect/token/introspect",
  "oidc.introspect.credentials": "Bearer ewjiwelhwew",
  "oidc.audience": "webadmin-proxy",
  "oidc.claim.authenticated.user": "email",
  "oidc.token.cache.expiration": "360s",
  "clients": {
    "clientIdA": {
      "webadmin.backend": "http://127.0.0.1:8000",
      "webadmin.token": "mastertokenvalue",
      "expected.claims": {
        "admin": "1"
      }
    },
    "clientIdB": {
      "webadmin.backend": "http://127.0.0.1:8001",
      "webadmin.token": "readonlytoken",
      "allowed.urls": [
        {"verb": ["GET", "PUT"], "endpoint": "/users"},
        {"endpoint": "/domains/{domain}/aliases/*"}
      ]
    }
  }
}
```

If no allowed.url is specified then all are allowed!

#### Endpoint pattern syntax

| Token | Meaning |
|-------|---------|
| `{varname}` | Captures exactly one path segment (no `/`) |
| `%` | Captures the local part of an email address (no `@`, no `/`) |
| `*` | Matches any remaining characters, including `/` |
| `?param={varname}` | Captures a query parameter value into a named variable |
| `?param=value` | Requires query parameter to equal a literal value |

`{varname}` and `%@{varname}` captured values are available to `url.patterns.restrictions`. Query parameters require their full name to be present in the request; extra query parameters are ignored.

If `verb` is omitted from a rule, all HTTP verbs are allowed.

Definition of done: Write IT tests.


### Step 5: domain validation

We want to have for some clients extra validation of the url templates variables. Goal is of course to scope admin operation to the actual domain of the user.

Eg:

Note: `oidc.introspect.credentials` is optional.

```json
{
  "port": "8001",
  "oidc.userInfo.url": "http://keycloak:8080/auth/realms/oidc/protocol/openid-connect/token/userinfo",
  "oidc.introspect.url": "http://keycloak:8080/auth/realms/oidc/protocol/openid-connect/token/introspect",
  "oidc.introspect.credentials": "Bearer ewjiwelhwew",
  "oidc.audience": "webadmin-proxy",
  "oidc.claim.authenticated.user": "email",
  "oidc.token.cache.expiration": "360s",
  "clients": {
    "clientIdA": {
      "webadmin.backend": "http://127.0.0.1:8000",
      "webadmin.token": "mastertokenvalue",
      "expected.claims": {
        "admin": "1"
      }
    },
    "clientIdB": {
      "webadmin.backend": "http://127.0.0.1:8001",
      "webadmin.token": "readonlytoken",
      "url.patterns.restrictions": {
        "domain": {
          "backing.claim": "domain",
          "operator": "EQUALS"
        }
      },
      "allowed.urls": [
        {"verb": ["GET"], "endpoint": "/domains/{domain}/users"},
        {"endpoint": "/domains/{domain}/aliases/*"}
      ]
    }
  }
}
```

Supported url.patterns.restrictions operator:
 - EQUALS: Exactly the value
 - HAS_DOMAIN: parses the domain of the mail address. To be used with claim `email`

If the claim referenced by `backing.claim` is absent from the token, the request MUST be rejected with 403. A missing claim must never silently pass as unrestricted access.

Definition of done: Write IT tests.

### Step 5.5: Email local-part wildcard for user endpoints

James user endpoints address users by their full email address (e.g. `GET /users/bob@example.com/mailboxes`). The `%` wildcard in endpoint patterns matches the local part of an email (any characters except `@` and `/`), so that `%@{domain}` binds the domain segment for use in `url.patterns.restrictions`.

This enables per-domain scoping of user endpoints without enumerating every possible username.

Example: a client that may only manage users belonging to its own domain:

```json
"clientIdB": {
  "webadmin.backend": "http://127.0.0.1:8001",
  "webadmin.token": "readonlytoken",
  "url.patterns.restrictions": {
    "domain": {
      "backing.claim": "email",
      "operator": "HAS_DOMAIN"
    }
  },
  "allowed.urls": [
    {"endpoint": "/users/%@{domain}/*"}
  ]
}
```

An admin whose `email` claim is `alice@example.com` can reach `/users/bob@example.com/mailboxes` (domain matches) but NOT `/users/bob@other.com/mailboxes` (domain mismatch → 403).

The `%` token captures no named variable — it is a pure consumer of the local part. Only `{domain}` is captured and passed to `url.patterns.restrictions`.

Definition of done: Write IT tests.

### Step 5.75: Authorized users

On some environment it is hard to configure OIDC servers and we can rely on a small static list of authorized users. Defualts to none if ommited which translates to no specific restrictions.

Syntax:

```json
{
  "port": "8001",
  "oidc.userInfo.url": "http://keycloak:8080/auth/realms/oidc/protocol/openid-connect/token/userinfo",
  "oidc.introspect.url": "http://keycloak:8080/auth/realms/oidc/protocol/openid-connect/token/introspect",
  "oidc.introspect.credentials": "Bearer ewjiwelhwew",
  "oidc.audience": "webadmin-proxy",
  "oidc.claim.authenticated.user": "email",
  "oidc.token.cache.expiration": "360s",
  "clients": {
    "clientIdA": {
      "webadmin.backend": "http://127.0.0.1:8000",
      "webadmin.token": "mastertokenvalue",
      "expected.claims": {
        "admin": "1"
      }
    },
    "clientIdB": {
      "webadmin.backend": "http://127.0.0.1:8001",
      "webadmin.token": "readonlytoken",
      "url.patterns.restrictions": {
        "domain": {
          "backing.claim": "domain",
          "operator": "EQUALS"
        }
      },
      "authorized.users":["btellier@linagora.com","xguimard@linagora.com"],
      "allowed.urls": [
        {"verb": ["GET"], "endpoint": "/domains/{domain}/users"},
        {"endpoint": "/domains/{domain}/aliases/*"}
      ]
    }
  }
}
```


### Step 6: Expose backchannel logout

The proxy exposes its own admin API (distinct from the proxied James webadmin). For now it exposes a single endpoint: backchannel logout, allowing the OIDC provider to invalidate cached tokens upon session termination.

The admin port is configured via `self.webadmin.port` in main `configuration.json` :

```json
{
  ...
  "self.webadmin.port": "8002"
  ...
}
```

Set up the proxy's admin HTTP server CF /home/hp/Documents/twake-calendar/app/src/main/java/com/linagora/calendar/app/TwakeCalendarMain.java

Set up backchannel logout route CF /home/hp/Documents/twake-calendar/calendar-webadmin/src/main/java/com/linagora/calendar/webadmin/CalendarChannelLogoutRoutes.java

Test backchannel.

Definition of done: Write IT tests.

### Step 7: Observability

The proxy's admin API shall expose:
 - **Metrics**: use `org.apache.james.metrics.api` for recording metrics, exposed via `org.apache.james.webadmin.dropwizard.MetricsRoutes` on the admin HTTP server CF /home/hp/Documents/twake-calendar/app/src/main/java/com/linagora/calendar/app/TwakeCalendarMain.java
 - **Healthcheck API**: expose the standard James healthcheck routes on the admin HTTP server

Definition of done: Write IT tests for both endpoints.

### Step 8: Advertize the configuration to clients

Resolve: `GET /.proxy/allowed/urls`

Authenticate with OIDC, and serve the list of `allowed.urls` associated with the client ID.

Goal is to enable frontend to adapt their UX with their permission level (self adaptative frontend).

Response:

```
[
    {"verb": ["GET"], "endpoint":"/domains/{domain}/users"},
    {"endpoint":"/domains/{domain}/aliases/*"}
]
```

Answer 204 no content if this field is not configured.

Definition of done: Write IT tests.

### Step 9: Documentation

Write a README skeleton inspired AND adapted from /home/hp/Documents/twake-calendar/README.md

Write a concise but complete `/docs` folder with the following pages:
 - `00-motivations.md`
 - `01-architecture.md`
 - `02-configuration.md`
 - `03-build.md`
 - `04-run-with-docker.md`

### Step 10: Proxy self-service info endpoints

Enable frontends to retrieve contextual information about the authenticated user without parsing OIDC tokens directly.

#### Endpoints

| Endpoint | Response |
|----------|----------|
| `GET /.proxy/whoami` | `{"email":"btellier@linagora.com"}` |
| `GET /.proxy/myDomain` | `{"domain":"linagora.com"}` |

Both endpoints require a valid Bearer token and apply the same `authorized.users` check as regular proxy requests. They are never forwarded to the James backend.

#### Domain resolution logic for `myDomain`

1. If the `domain` claim is present in userinfo, use its value directly.
2. Otherwise, extract the domain part of the email claim (everything after `@`).
3. If neither is available (no `domain` claim and user identity is not an email address), return 403.

#### Goal

Allow Twake Mail functional admin frontend to auto-populate user context (current user identity, managed domain) without requiring the frontend to parse or introspect OIDC tokens.

Definition of done: Write IT tests.

### Step 11: Inline deny rules in allowed.urls

Extend `allowed.urls` to support a `"denied": true` flag on any rule. Rules are evaluated **in order**; the first matching rule wins. A matching denied rule returns 403 immediately.

This allows compact configs where a broad wildcard covers most paths and a few explicit deny rules carve out exceptions, without introducing a separate `denied.urls` list.

#### Syntax

```json
"allowed.urls": [
  {"denied": true, "endpoint": "/domains/{domain}/quota"},
  {"denied": true, "verb": ["DELETE"], "endpoint": "/domains/{domain}/aliases"},
  {"endpoint": "/domains/{domain}/*"}
]
```

- `denied` is optional, defaults to `false`
- `verb` is optional on deny rules, same as on allow rules — omitting it matches all verbs
- Order is significant: place deny rules before the broader allow rules they override

#### Semantics

| First matching rule | Outcome |
|---------------------|---------|
| `denied: true` | 403 Forbidden |
| `denied: false` (or absent) | request allowed, pattern variables passed to `url.patterns.restrictions` |
| No match | 403 Forbidden (same as before) |

#### `/.proxy/allowed/urls` response

Deny rules are included in the response with `"denied": true` so that frontends can reflect the full ACL. Rules without the flag omit the `denied` key entirely.

Definition of done: Write IT tests.

### Step 12: Clients as an ordered array with duplicate client_id support

Change the `clients` configuration field from a JSON object (unique keys) to a JSON array of single-key objects. This allows the same `client_id` to appear more than once, each entry carrying different `authorized.users`, `expected.claims`, backend, or URL restriction configuration.

#### Motivation

A single OIDC client can represent users with different permission levels. For example, support staff, regular admin and Privacy Officers may share the same OIDC client but require different backend routing and URL access controls. Duplicating the `client_id` key in an object is not valid JSON; an ordered array is the correct representation.

#### Configuration format

Before (unique keys, one entry per client_id):

```json
"clients": {
  "twakemail-client": {
    "webadmin.backend": "http://james-a:8000",
    "webadmin.token": "token-a"
  }
}
```

After (array of single-key objects, duplicate keys allowed):

```json
"clients": [
  {
    "twakemail-client": {
      "webadmin.backend": "http://james-a:8000",
      "webadmin.token": "token-a",
      "authorized.users": ["alice@example.com"]
    }
  },
  {
    "twakemail-client": {
      "webadmin.backend": "http://james-b:8000",
      "webadmin.token": "token-b",
      "authorized.users": ["bob@example.com"]
    }
  }
]
```

#### Selection semantics

For a given token, the proxy:

1. Extracts `client_id` from the introspect response.
2. Iterates the `clients` array in order.
3. Picks the **first entry** whose key matches the `client_id` AND for which **both** `authorized.users` and `expected.claims` match the authenticated user.
4. If no entry matches → 403 Forbidden.

#### Definition of done: Write IT tests covering

- A token for user A routes to entry 1; a token for user B routes to entry 2.
- A token whose user matches no entry returns 403.
- When both `authorized.users` and `expected.claims` are used as criteria, only entries satisfying both are selected.
- When multiple entries match, the first one in the array wins.
