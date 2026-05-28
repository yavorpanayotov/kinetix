package com.kinetix.regulatory.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * A pricing or risk model that has been approved by Compliance and used
 * to compute a regulatory report is the snapshot of model code, model
 * parameters, and supporting documents that Compliance signed off on.
 * That snapshot must not mutate after sign-off: if the model lineage in
 * a SAR (Supervisory Audit Record) points to v2.3, then v2.3 must stay
 * frozen — the next iteration becomes v2.4. Editing v2.3 in place would
 * invalidate every historical report that cites it.
 *
 * The test pins down the invariant: an APPROVED [ModelVersion] rejects
 * any attempt to mutate its parameters, returns a successor when asked
 * for a new edit, and the immutability check works irrespective of
 * call-site (the data class is a `val`-only structure to enforce this
 * at compile time too).
 */
class ModelVersionImmutabilityTest : FunSpec({

    test("an APPROVED ModelVersion exposes immutable fields (val-only)") {
        val v23 = ModelVersion(
            name = "fx-vol-surface",
            version = "2.3",
            parameters = mapOf("smile" to "sabr", "interp" to "spline"),
            status = ModelVersionStatus.APPROVED,
        )
        // Both fields are read-only; mutation would not compile. Confirm via
        // the public accessor.
        v23.version shouldBe "2.3"
        v23.parameters["smile"] shouldBe "sabr"
    }

    test("withParameters() on an APPROVED version throws") {
        val v23 = ModelVersion(
            name = "fx-vol-surface",
            version = "2.3",
            parameters = mapOf("smile" to "sabr"),
            status = ModelVersionStatus.APPROVED,
        )
        shouldThrow<IllegalStateException> {
            v23.withParameters(mapOf("smile" to "svi"))
        }
    }

    test("withParameters() on a DRAFT version returns a new instance with the updated parameters") {
        val draft = ModelVersion(
            name = "fx-vol-surface",
            version = "2.4-draft",
            parameters = mapOf("smile" to "sabr"),
            status = ModelVersionStatus.DRAFT,
        )
        val updated = draft.withParameters(mapOf("smile" to "svi"))
        updated.parameters["smile"] shouldBe "svi"
        // Original is untouched even though DRAFT.
        draft.parameters["smile"] shouldBe "sabr"
    }

    test("succeedingDraft() returns a DRAFT successor without mutating the source") {
        val v23 = ModelVersion(
            name = "fx-vol-surface",
            version = "2.3",
            parameters = mapOf("smile" to "sabr"),
            status = ModelVersionStatus.APPROVED,
        )
        val v24 = v23.succeedingDraft(newVersion = "2.4")
        v24.name shouldBe v23.name
        v24.version shouldBe "2.4"
        v24.parameters shouldBe v23.parameters
        v24.status shouldBe ModelVersionStatus.DRAFT
        // Source remains APPROVED with its original parameters.
        v23.status shouldBe ModelVersionStatus.APPROVED
        v23.version shouldBe "2.3"
    }

    test("the parameter map exposed by an APPROVED version is read-only at runtime") {
        val v23 = ModelVersion(
            name = "fx-vol-surface",
            version = "2.3",
            parameters = mapOf("smile" to "sabr"),
            status = ModelVersionStatus.APPROVED,
        )
        // Attempting to cast to MutableMap and mutate must fail.
        shouldThrow<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (v23.parameters as MutableMap<String, String>)["smile"] = "svi"
        }
    }
})
