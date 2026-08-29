# TODO

Known gaps, prioritized. See README.md for architecture context and the
initial commit message for full reasoning behind each fix in this pass.

## Testing

- [x] ~~No full context-load / integration test~~ - resolved:
      `RouteEndpointsIT` boots the real Spring context (`dev,test`
      profiles - the real route table under test, not a stripped-down
      test-only copy) and proves every route actually forwards an HTTP
      request to its intended backend, not just that the config parses.
      One shared WireMock instance stands in for all 14 distinct `lb://`
      service names (Spring Cloud LoadBalancer's "simple" discovery
      client, registered via `@DynamicPropertySource` once WireMock's
      port is known - no Testcontainers, no real Eureka needed); each
      route's own path predicate is what has to correctly steer a
      request to the right stub, which is the thing actually worth
      proving here. 37 cases: every authenticated route's happy path and
      its unauthenticated-gets-401-never-reaches-backend counterpart, the
      two route-ordering collisions (device-control-service vs.
      device-management-service, the WS-vs-REST telemetry-stream-service
      split including the StripPrefix behavior), the super-admin role
      gate (both directions), CORS preflight, and an unmapped path
      getting a clean 404. Bound to `maven-failsafe-plugin` (`*IT.java`),
      so `mvn test` stays fast/network-free and only `mvn verify` runs
      it - needs a real local Redis (`RequestRateLimiter` is wired into
      most routes), same assumption every other check on this platform
      already makes.

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
