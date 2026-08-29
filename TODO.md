# TODO

Known gaps, prioritized. See README.md for architecture context and the
initial commit message for full reasoning behind each fix in this pass.

## Testing

- [ ] **No full context-load / integration test.** `SecurityConfigTest`,
      `RateLimiterConfigTest`, `ParamBlockerFilterTest`, and
      `FallbackControllerTest` cover the real custom logic as focused unit
      tests, but nothing boots the actual Spring context and exercises a
      real route end-to-end in CI - this service needs a live Redis
      (RequestRateLimiter) and ideally Eureka to do that meaningfully,
      which means Testcontainers, not yet set up here. Everything was
      instead verified with a real local boot against real running
      services during development (real login through auth-service, a
      real user-settings-service round trip, the device-control-service/
      device-management-service route collision, actuator security) - see
      the initial commit message - but that verification doesn't run
      again on every future change the way an automated test would.

## Product / design

- [ ] **11 services have no gateway route at all** (see README's route
      table / this session's own survey): some have no live REST
      controller yet (`ai-engine`, `iot-protocol-gateway` are stub
      modules; `report-generation-service` has scaffolding but no active
      controller), some are intentionally non-HTTP (`brain-service`
      Kafka Streams, `telemetry-normalization-service` RabbitMQ consumer,
      `influxdb-notification` a scheduled poller), and some are Go/Python
      services not registered with Eureka (`data-ingestion-service`,
      `media-ingestion-service`, `ai-worker-service`, `rtsp-python-service`,
      `flink-threshold-service`). Add a route for any of these the moment
      it actually gets a real, Eureka-registered REST API.
- [ ] **`device-control-service` vs `device-management-service`** both
      claim `/api/v1/devices/**` as a base path - resolved here via route
      ordering (more specific first), but the underlying path design
      overlap between the two services isn't fixed. Worth a real rename on
      one side eventually.
- [ ] **`ParamBlockerFilter` still isn't wired into any route** - fixed
      (it would have failed to bind at all before), but nothing currently
      uses it. Available if a specific traffic pattern needs blocking
      without a deploy.

## Operational

- [ ] Rate limits in `application-dev.yml`/`application-prod.yml` are
      reasonable starting points, not measured - re-tune from real
      Prometheus traffic data once this is actually live in front of real
      traffic.
- [ ] `nginx -t` / the `iiotedge-cli.sh` Nginx config change can only be
      verified on a real server - this sandbox has no Nginx to test
      against. Verify manually before relying on it in production.
