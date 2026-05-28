package com.kinetix.regulatory.model

import java.util.Collections

/**
 * A pricing/risk-model snapshot tracked by the model-governance registry.
 *
 * An [APPROVED][ModelVersionStatus.APPROVED] version is the snapshot of
 * model code and parameters that Compliance has signed off on for use in
 * regulatory reports. It is immutable: any attempt to change parameters
 * on an APPROVED version throws — the next iteration becomes a
 * [succeedingDraft] with a bumped version number, which can be edited
 * freely until it is itself approved.
 *
 * Field-level immutability comes from `val` on every property. The
 * parameter map is wrapped in an unmodifiable view so even a runtime
 * cast to `MutableMap` cannot mutate it.
 */
class ModelVersion(
    val name: String,
    val version: String,
    parameters: Map<String, String>,
    val status: ModelVersionStatus,
) {
    val parameters: Map<String, String> =
        Collections.unmodifiableMap(LinkedHashMap(parameters))

    /**
     * Return a new instance with the given parameters. Throws if this
     * version is APPROVED (immutable post-sign-off).
     */
    fun withParameters(parameters: Map<String, String>): ModelVersion {
        check(status != ModelVersionStatus.APPROVED) {
            "ModelVersion ${name}@${version} is APPROVED and cannot mutate; " +
                "create a succeedingDraft() instead"
        }
        return ModelVersion(
            name = name,
            version = version,
            parameters = parameters,
            status = status,
        )
    }

    /** Return a DRAFT successor at [newVersion] preserving this version's parameters. */
    fun succeedingDraft(newVersion: String): ModelVersion = ModelVersion(
        name = name,
        version = newVersion,
        parameters = parameters,
        status = ModelVersionStatus.DRAFT,
    )
}

/** Lifecycle status of a [ModelVersion]. */
enum class ModelVersionStatus {
    DRAFT,
    PENDING_REVIEW,
    APPROVED,
    DEPRECATED,
}
