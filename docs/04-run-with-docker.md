# Run with Docker

## Build the image

```bash
mvn compile jib:dockerBuild
```

This produces a `linagora/webadmin-proxy:latest` image with the sample `configuration.json` bundled at `/root/conf/configuration.json`.

## Minimal run

```bash
docker run \
  -p 8001:8001 \
  -e OIDC_INTROSPECT_CREDENTIALS="<oidc-client-secret>" \
  -e TWAKEMAIL_WEBADMIN_TOKEN="<james-admin-token>" \
  linagora/webadmin-proxy
```

The bundled configuration points to `http://127.0.0.1:8180` for OIDC and `http://127.0.0.1:8000` for the James backend — suitable only for host-network scenarios or local testing. For real deployments, mount a custom configuration file.

## Mount a custom configuration

```bash
docker run \
  -p 8001:8001 \
  -v /path/to/your/configuration.json:/root/conf/configuration.json:ro \
  -e OIDC_INTROSPECT_CREDENTIALS="<secret>" \
  -e TWAKEMAIL_WEBADMIN_TOKEN="<token>" \
  linagora/webadmin-proxy
```

## With self-admin enabled

To expose metrics and health-check endpoints, enable `self.webadmin.enabled` and map the admin port:

```json
{
  "self.webadmin.enabled": "true",
  "self.webadmin.port": "8002"
}
```

```bash
docker run \
  -p 8001:8001 \
  -p 8002:8002 \
  -v /path/to/configuration.json:/root/conf/configuration.json:ro \
  -e OIDC_INTROSPECT_CREDENTIALS="<secret>" \
  -e TWAKEMAIL_WEBADMIN_TOKEN="<token>" \
  linagora/webadmin-proxy
```

Then:

```bash
curl http://localhost:8002/healthcheck
curl http://localhost:8002/metrics
```

## Docker Compose example

```yaml
services:
  webadmin-proxy:
    image: linagora/webadmin-proxy:latest
    ports:
      - "8001:8001"
      - "8002:8002"
    volumes:
      - ./configuration.json:/root/conf/configuration.json:ro
    environment:
      OIDC_INTROSPECT_CREDENTIALS: "${OIDC_INTROSPECT_CREDENTIALS}"
      TWAKEMAIL_WEBADMIN_TOKEN: "${TWAKEMAIL_WEBADMIN_TOKEN}"
    restart: unless-stopped
```

## Environment variables

Sensitive values should always be passed as environment variables and referenced in `configuration.json` using `{ENV:VAR_NAME}`. Never embed credentials directly in the configuration file.

| Variable | Used for |
|----------|----------|
| `OIDC_INTROSPECT_CREDENTIALS` | Value of `oidc.introspect.credentials` |
| `TWAKEMAIL_WEBADMIN_TOKEN` | Value of `webadmin.token` for each client |

Additional environment variables can be introduced freely by adding `{ENV:MY_VAR}` references to your configuration file.
