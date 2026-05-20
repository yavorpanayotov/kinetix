package com.kinetix.position.metrics

import com.kinetix.position.model.LimitDefinition
import com.kinetix.position.model.LimitLevel
import com.kinetix.position.model.LimitType
import com.kinetix.position.persistence.LimitDefinitionRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.mockk
import java.math.BigDecimal

private fun varLimit(
    id: String,
    entityId: String,
    limitValue: String,
    level: LimitLevel = LimitLevel.BOOK,
    limitType: LimitType = LimitType.VAR,
    active: Boolean = true,
) = LimitDefinition(
    id = id,
    level = level,
    entityId = entityId,
    limitType = limitType,
    limitValue = BigDecimal(limitValue),
    intradayLimit = null,
    overnightLimit = null,
    active = active,
)

class VarLimitGaugeBinderTest : FunSpec({

    val limitDefinitionRepo = mockk<LimitDefinitionRepository>()

    beforeEach {
        clearMocks(limitDefinitionRepo)
    }

    test("registers a risk_var_limit gauge for each book's configured VaR limit") {
        coEvery { limitDefinitionRepo.findAll() } returns listOf(
            varLimit(id = "book-a-var", entityId = "book-a", limitValue = "800000"),
            varLimit(id = "book-b-var", entityId = "book-b", limitValue = "10000000"),
        )
        val registry = SimpleMeterRegistry()
        val binder = VarLimitGaugeBinder(limitDefinitionRepo, registry)

        binder.refresh()

        registry.find("risk_var_limit").tag("book_id", "book-a").gauge()
            .shouldNotBeNull().value() shouldBe 800000.0
        registry.find("risk_var_limit").tag("book_id", "book-b").gauge()
            .shouldNotBeNull().value() shouldBe 10000000.0
    }

    test("tags the gauge with book_id, calculation_type and confidence_level") {
        coEvery { limitDefinitionRepo.findAll() } returns listOf(
            varLimit(id = "book-a-var", entityId = "book-a", limitValue = "500000"),
        )
        val registry = SimpleMeterRegistry()
        val binder = VarLimitGaugeBinder(limitDefinitionRepo, registry)

        binder.refresh()

        val gauge = registry.find("risk_var_limit")
            .tag("book_id", "book-a")
            .tag("calculation_type", "ALL")
            .tag("confidence_level", "ALL")
            .gauge()
        gauge.shouldNotBeNull()
        gauge.value() shouldBe 500000.0
    }

    test("ignores non-VAR limit types") {
        coEvery { limitDefinitionRepo.findAll() } returns listOf(
            varLimit(
                id = "book-a-notional",
                entityId = "book-a",
                limitValue = "10000000",
                limitType = LimitType.NOTIONAL,
            ),
        )
        val registry = SimpleMeterRegistry()
        val binder = VarLimitGaugeBinder(limitDefinitionRepo, registry)

        binder.refresh()

        registry.find("risk_var_limit").gauge() shouldBe null
    }

    test("ignores inactive VaR limits") {
        coEvery { limitDefinitionRepo.findAll() } returns listOf(
            varLimit(
                id = "book-a-var",
                entityId = "book-a",
                limitValue = "800000",
                active = false,
            ),
        )
        val registry = SimpleMeterRegistry()
        val binder = VarLimitGaugeBinder(limitDefinitionRepo, registry)

        binder.refresh()

        registry.find("risk_var_limit").gauge() shouldBe null
    }

    test("reflects an updated VaR limit value on the next refresh without re-registering the gauge") {
        coEvery { limitDefinitionRepo.findAll() } returns listOf(
            varLimit(id = "book-a-var", entityId = "book-a", limitValue = "800000"),
        )
        val registry = SimpleMeterRegistry()
        val binder = VarLimitGaugeBinder(limitDefinitionRepo, registry)

        binder.refresh()

        coEvery { limitDefinitionRepo.findAll() } returns listOf(
            varLimit(id = "book-a-var", entityId = "book-a", limitValue = "1200000"),
        )
        binder.refresh()

        registry.find("risk_var_limit").tag("book_id", "book-a").gauges().size shouldBe 1
        registry.find("risk_var_limit").tag("book_id", "book-a").gauge()
            .shouldNotBeNull().value() shouldBe 1200000.0
    }
})
