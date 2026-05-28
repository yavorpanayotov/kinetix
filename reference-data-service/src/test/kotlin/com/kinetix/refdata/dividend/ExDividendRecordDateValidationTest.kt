package com.kinetix.refdata.dividend

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDate

/**
 * Corporate-action calendars are sensitive to the relative ordering of
 * the ex-dividend date (after which a buyer is not entitled to the
 * upcoming dividend) and the record date (the cut-off for the issuer's
 * register of shareholders). Per market convention, ex-dividend is
 * always at or before the record date — typically one trading day
 * before. A feed that inverts the two will mis-attribute the dividend
 * to the wrong cohort of holders. The validator pins down the contract.
 */
class ExDividendRecordDateValidationTest : FunSpec({

    val recordDate = LocalDate.of(2026, 6, 15)

    test("accepts ex-dividend one day before record date (typical case)") {
        validateExDividendRecordDates(
            exDividendDate = recordDate.minusDays(1),
            recordDate = recordDate,
        )
    }

    test("accepts ex-dividend exactly equal to record date (boundary)") {
        validateExDividendRecordDates(recordDate, recordDate)
    }

    test("accepts ex-dividend a week before record date") {
        validateExDividendRecordDates(recordDate.minusDays(7), recordDate)
    }

    test("rejects ex-dividend one day after record date") {
        shouldThrow<IllegalArgumentException> {
            validateExDividendRecordDates(recordDate.plusDays(1), recordDate)
        }
    }

    test("rejection message names both dates and the offending order") {
        val ex = shouldThrow<IllegalArgumentException> {
            validateExDividendRecordDates(recordDate.plusDays(1), recordDate)
        }
        ex.message!!.contains("ex-dividend") shouldBe true
        ex.message!!.contains("record") shouldBe true
    }
})
