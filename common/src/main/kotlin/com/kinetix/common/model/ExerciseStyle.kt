package com.kinetix.common.model

import kotlinx.serialization.Serializable

/**
 * Canonical option exercise style shared across all Kinetix services.
 *
 * Declared in `core.allium` as `enum ExerciseStyle { american | european }`.
 */
@Serializable
enum class ExerciseStyle {
    AMERICAN,
    EUROPEAN,
}
