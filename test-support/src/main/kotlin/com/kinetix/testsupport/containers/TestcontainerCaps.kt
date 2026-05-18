package com.kinetix.testsupport.containers

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.kafka.KafkaContainer

/**
 * Resource caps applied to per-test Testcontainers so concurrent test JVMs
 * don't oversubscribe a developer laptop.
 *
 * These caps are test-only — production sizing lives in Helm, and the
 * dev/demo stack (`infra/docker-compose.infra.yml`) is unaffected. Helpers
 * are wired through the `*TestSetup.kt` classes that back acceptance,
 * integration, and end-to-end tests.
 *
 * Postgres tuning includes "ephemeral container only" flags
 * (`fsync=off`, `synchronous_commit=off`, `full_page_writes=off`) that are
 * unsafe for real workloads but appropriate here: containers are
 * destroyed after the test JVM exits.
 */
object TestcontainerCaps {

    private const val MB: Long = 1024L * 1024L

    /**
     * Cap a Postgres test container at ~384 MB resident, 1 CPU, with a
     * lean config tuned for ephemeral test workloads. Set
     * [withTimescale] = true when the container image is a TimescaleDB
     * variant — preserves `shared_preload_libraries=timescaledb` so the
     * extension and continuous aggregates keep working.
     *
     * Mutates and returns [container] for fluent chaining.
     */
    fun tunePostgres(
        container: PostgreSQLContainer<*>,
        withTimescale: Boolean = false,
        memoryMb: Long = 384,
        cpus: Double = 1.0,
    ): PostgreSQLContainer<*> {
        val args = buildList {
            add("postgres")
            addAll(listOf("-c", "max_connections=20"))
            addAll(listOf("-c", "shared_buffers=64MB"))
            addAll(listOf("-c", "work_mem=4MB"))
            addAll(listOf("-c", "maintenance_work_mem=32MB"))
            addAll(listOf("-c", "effective_cache_size=128MB"))
            addAll(listOf("-c", "wal_buffers=8MB"))
            addAll(listOf("-c", "max_wal_size=128MB"))
            addAll(listOf("-c", "fsync=off"))
            addAll(listOf("-c", "synchronous_commit=off"))
            addAll(listOf("-c", "full_page_writes=off"))
            if (withTimescale) {
                addAll(listOf("-c", "shared_preload_libraries=timescaledb"))
            }
        }
        container.withCommand(*args.toTypedArray())
        applyDockerCaps(container, memoryMb, cpus)
        return container
    }

    /**
     * Cap a Kafka test container at ~512 MB resident, 1 CPU, with a 256 MB
     * JVM heap. Kafka also leans on the OS page cache for log segments,
     * which is counted against the cgroup memory limit — leaving headroom
     * beyond the JVM heap is intentional.
     *
     * Mutates and returns [container].
     */
    fun tuneKafka(
        container: KafkaContainer,
        memoryMb: Long = 512,
        cpus: Double = 1.0,
    ): KafkaContainer {
        container.withEnv("KAFKA_HEAP_OPTS", "-Xms128m -Xmx256m")
        applyDockerCaps(container, memoryMb, cpus)
        return container
    }

    /**
     * Cap a Redis test container at ~128 MB resident, 0.5 CPU. Persistence
     * is disabled (`--save ""`, `--appendonly no`) to avoid BGSAVE memory
     * spikes — tests don't need snapshots.
     *
     * Mutates and returns [container].
     */
    fun tuneRedis(
        container: GenericContainer<*>,
        memoryMb: Long = 128,
        cpus: Double = 0.5,
        maxmemoryMb: Long = 64,
    ): GenericContainer<*> {
        container.withCommand(
            "redis-server",
            "--maxmemory", "${maxmemoryMb}mb",
            "--maxmemory-policy", "allkeys-lru",
            "--save", "",
            "--appendonly", "no",
        )
        applyDockerCaps(container, memoryMb, cpus)
        return container
    }

    private fun applyDockerCaps(container: GenericContainer<*>, memoryMb: Long, cpus: Double) {
        val nanoCpus = (cpus * 1_000_000_000L).toLong()
        container.withCreateContainerCmdModifier { cmd ->
            cmd.hostConfig
                ?.withMemory(memoryMb * MB)
                ?.withNanoCPUs(nanoCpus)
        }
    }
}
