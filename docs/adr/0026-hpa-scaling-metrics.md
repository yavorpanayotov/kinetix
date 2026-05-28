# ADR-0026: HPA Scaling Metrics Strategy

## Status
Accepted (2026-05-28). Rollout pending — all 16 HPA templates currently ship CPU-only; the memory + kafka_consumer_lag changes are tracked separately in beads.

## Context
All Kinetix services currently use CPU utilisation as the sole HPA metric (80% target). In practice, CPU is a poor proxy for load in two common scenarios:

1. **Memory-bound services**: The gateway and risk-orchestrator accumulate in-flight response buffers and deserialized position data. Memory pressure can exhaust heap and trigger GC storms long before CPU crosses the 80% threshold.

2. **Consumer-lag-driven backlog**: Kafka consumer services (risk-orchestrator, notification-service, audit-service, position-service) can fall behind on their topics without the pods becoming CPU-hot. A burst of 50,000 trade events fills consumer lag while CPU stays modest — the correct response is to scale up consumers, but the CPU-only HPA stays flat.

The consequence in both cases is a period where the system is under real load pressure but no scaling action is taken, leading to degraded latency, GC pauses, or growing consumer lag that compounds into downstream SLO breaches.

## Decision
Extend all HPAs to use **at least two metrics**: CPU and memory. For services that consume Kafka topics, add a third metric — Kafka consumer lag — sourced via the Prometheus adapter.

### Memory metric (all services)
Add `memory` as a resource metric alongside CPU, with a target of 80% of the container's configured memory limit. This ensures pods scale out when heap approaches the limit rather than after GC pressure has already degraded latency.

### Kafka consumer lag metric (consumer services)
For services with Kafka consumers (risk-orchestrator, notification-service, audit-service, position-service), add a custom metric `kafka_consumer_lag_sum` sourced from the Prometheus adapter. A target average value of 10,000 messages per replica triggers a scale-out. This prevents a consumer backlog from growing unchecked while CPU remains calm.

The Prometheus adapter requires `kube-prometheus-stack` (already in the stack via the observability chart) and a `ConfigMap` mapping the Prometheus metric to a Kubernetes custom metric. The consumer lag metric is emitted by the Kafka exporter (JMX exporter sidecar on the Kafka broker pods).

### Example multi-metric HPA Helm template

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: {{ include "risk-orchestrator.fullname" . }}
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: {{ include "risk-orchestrator.fullname" . }}
  minReplicas: {{ .Values.autoscaling.minReplicas }}
  maxReplicas: {{ .Values.autoscaling.maxReplicas }}
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 80
    - type: Resource
      resource:
        name: memory
        target:
          type: Utilization
          averageUtilization: 80
    - type: Pods
      pods:
        metric:
          name: kafka_consumer_lag_sum
        target:
          type: AverageValue
          averageValue: "10000"
```

Non-consumer services (gateway, reference-data-service, regulatory-service) use only CPU and memory:

```yaml
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 80
    - type: Resource
      resource:
        name: memory
        target:
          type: Utilization
          averageUtilization: 80
```

## Applies when
- Creating or editing a service's HPA in its Helm chart under `deploy/`.
- Setting JVM heap (`-Xmx`) or container memory limits.
- Operating a service that consumes a Kafka topic.

## Rules
- **DO** include **both** CPU and memory metrics on every HPA (target 80% on each).
- **DO** add the `kafka_consumer_lag_sum` Pods metric to HPAs for Kafka-consumer services (risk-orchestrator, notification-service, audit-service, position-service). Target `averageValue: "10000"` messages per replica.
- **DO** tune `-Xmx` and the container memory limit together before enabling the memory metric — large stable heaps inflate utilisation.
- **DO** verify the Prometheus adapter `CustomMetricsAPIService` mapping is in place before adding `kafka_consumer_lag_sum` to a new HPA. Without it, the HPA silently degrades.
- **DON'T** ship a new service HPA with CPU only. That has demonstrably failed for memory-bound and consumer-lag scenarios.
- **DON'T** introduce KEDA, custom controllers, or a parallel scaling system. `autoscaling/v2` + Prometheus adapter is the agreed path.
- **DON'T** set `minReplicas: 0` for consumer services — rebalances on every cold-start are operationally expensive.

## Consequences

### Positive
- Memory pressure triggers scale-out before GC storms degrade latency.
- Consumer lag scaling prevents audit, risk, and notification pipelines from falling behind during trade bursts.
- HPA `autoscaling/v2` is already the API version in use — no API upgrade required.
- Both CPU and memory are resource metrics with no external dependencies.

### Negative
- Kafka consumer lag metric requires the Prometheus adapter to be configured with a `CustomMetricsAPIService` mapping. This is an operational step that must be completed before the HPAs will function correctly for consumer services. Until it is in place, the lag metric must be omitted and added in a follow-on deployment.
- Memory-based scaling can be noisy if a service has a large but stable heap — a one-time startup allocation that stays live will permanently inflate memory utilisation. Memory limits and JVM heap settings (`-Xmx`) must be tuned before enabling the memory metric.
- HPA scale-in is always slower than scale-out (controlled by `stabilizationWindowSeconds`). Scaled-out consumer replicas will trigger Kafka partition rebalances; ensure consumer groups handle rebalancing gracefully (they do, via the existing `RetryableConsumer` implementation).

### Alternatives Considered
- **KEDA (Kubernetes Event-Driven Autoscaler)**: Purpose-built for event-driven scaling including Kafka lag. Provides richer trigger configuration and handles lag metrics natively without the Prometheus adapter. Rejected for now because it adds a new cluster-level dependency. Revisit if the Prometheus adapter approach proves brittle.
- **CPU only (status quo)**: Simple but demonstrably insufficient for memory-bound and consumer-lag scenarios.
- **Custom controller**: Write a bespoke scaling controller that reads Kafka consumer group offsets directly. Avoided because the HPA extension points already exist and a custom controller is significantly more maintenance burden.
