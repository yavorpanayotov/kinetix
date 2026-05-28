package com.kinetix.refdata.dividend

import java.time.LocalDate

/**
 * Validate that the ex-dividend date is on or before the record date.
 *
 * Per market convention, the ex-dividend date (after which a buyer is
 * not entitled to the upcoming dividend) is always at or before the
 * record date (the cut-off for the issuer's register of shareholders);
 * typically the ex-div date is one trading day before the record date.
 * A feed that inverts the two will mis-attribute the dividend to the
 * wrong holder cohort.
 *
 * @throws IllegalArgumentException if [exDividendDate] is after [recordDate].
 */
fun validateExDividendRecordDates(exDividendDate: LocalDate, recordDate: LocalDate) {
    require(!exDividendDate.isAfter(recordDate)) {
        "ex-dividend date $exDividendDate must not be after record date $recordDate"
    }
}
