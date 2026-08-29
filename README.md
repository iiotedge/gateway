# gateway (api-gateway)

Spring Cloud Gateway (WebFlux/reactive) fronting every IoTMining/IIoTEdge
microservice. This is the real production upstream Nginx routes to (see
`iiotedge-cli.sh`'s `do_configure_nginx` in the repo root) - Nginx no
longer proxies to each service's own port directly; it proxies everything
under `/api/v1/*` (and the notifications WebSocket) to this gateway, which
then owns routing to the right backend via Eureka.

## Why this exists

The route table existed before this pass but was, on inspection, largely
non-functional: every route stripped a short external prefix (e.g.
`/auth/**` → `StripPrefix=1` → `/**`) that didn't correspond to any real
controller path - nothing had ever actually routed through it to catch
that. This pass replaced the route table entirely (verified against each
service's real `@RequestMapping` base paths, not guessed), added the
resilience/security/observability that was mostly missing, and made the
gateway deployable the same way every other service is (`iiotedge-cli.sh`,
Docker/CI).

## Architecture

- **Routes** (`application-dev.yml`/`application-prod.yml`) - one per
  service base path, `uri: lb://<eureka-app-name>` (Eureka client-side load
  balancing), no `StripPrefix` except where a route's downstream really
  expects a shorter path (see the telemetry-stream-service split below).
  Two path collisions are resolved by declaration order (first match
  wins): `device-control-service`'s `/api/v1/devices/*/commands/**` is
  declared before `device-management-service`'s general
  `/api/v1/devices/**`, and `telemetry-stream-service`'s WebSocket route
  (`/api/v1/telemetry-stream/ws-telemetry/**`, unstripped - matches how
  its own `WebSocketConfig` registers that endpoint) is declared before
  its general REST route (`/api/v1/telemetry-stream/**`, `StripPrefix=3` -
  its real `/history` endpoint has no prefix at all).
- **`config/SecurityConfig`** - validates the JWT itself (HMAC, shared
  secret) via `JwtDecoderConfig`, same trust model every other service on
  this platform uses (downstream independently re-validates the same JWT
  rather than trusting a header this gateway would inject - defense in
  depth, not a single point of failure). Only `/actuator/health`,
  `/actuator/info`, `/fallback`, and `/api/v1/auth/**` (login/register
  can't require a pre-existing token) are open; everything else requires
  authentication, and `/api/v1/auth/super-admin/**` additionally requires
  `ROLE_SUPER_ADMIN` (read from the JWT's `role` claim - see
  `SecurityConfig.convertRoleClaim`, since Spring Security's default JWT
  authorities converter only reads a `scope`/`scp` claim, which this
  platform's tokens don't carry).
- **`config/RateLimiterConfig`** - the `KeyResolver` `RequestRateLimiter`
  needs (was missing entirely before this pass) - keys by JWT subject when
  authenticated, remote address otherwise.
- **Resilience** - every route has a tuned `CircuitBreaker` (shared
  `resilience4j.circuitbreaker.configs.default`, not Resilience4j's
  hardcoded defaults) and (where relevant) Redis-backed `RequestRateLimiter`.
  A global `Retry` filter (GET only - never retries a side-effecting call)
  and connect/response timeouts apply to every route via `default-filters`.
- **`controller/FallbackController`** - reached via every circuit breaker's
  `fallbackUri`, returns real JSON (error, message, path, correlation id,
  timestamp) instead of a bare string.
- **`filters/GlobalRequestLogger`** - structured SLF4J access log (method,
  path, status, latency) - previously a bare `System.out.println` of just
  the URI, bypassing the logging framework entirely.

## Local development

Requires Eureka (`localhost:8761`) and Redis (`localhost:6379`) - degrades
if Eureka is unavailable (routes just won't resolve), fails to start
without Redis (rate limiter is wired into most routes).

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Default port: `8057` (overridable via `SERVER_PORT`).

`SPRING_PROFILES_ACTIVE` defaults to `dev` even outside local development -
see `application.yml`'s own comment: this platform's actual production
deployment (`iiotedge-cli.sh`) runs every service, this one included, with
`SPRING_PROFILES_ACTIVE=dev` and supplies real production values purely
via env vars (`JWT_SECRET`, `REDIS_HOST`, etc.), never actually activating
`application-prod.yml`. That profile is kept for anyone who does run the
genuine prod profile by hand.

## Build

```bash
./mvnw clean verify
```

## Docker

```bash
./mvnw clean package -DskipTests
docker build -t api-gateway .
docker run -p 9057:9057 api-gateway
```

## License

MIT - see `LICENSE`.
