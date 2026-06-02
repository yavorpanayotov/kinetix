import type { CrossBookVaRResultDto, VaRResultDto } from '../types'

/**
 * kx-r2hj — Map a cross-book (firm/division/desk) VaR aggregate into the
 * single-book {@link VaRResultDto} shape that the VaR dashboard and gauge
 * consume.
 *
 * In aggregated hierarchy views the VaR gauge, Expected Shortfall headline and
 * Component Breakdown must reflect the diversified firm aggregate so they
 * reconcile with the Book Contributions "Sum of books" — rather than a stray
 * single-book result that App.tsx falls through to when no concrete book is
 * selected. The cross-book DTO already carries every field the dashboard reads
 * except the book-level Greeks / PV / valuation date, which have no firm
 * aggregate and are intentionally left undefined.
 *
 * `bookId` is set to the portfolio group id so any downstream consumer keying
 * off it (e.g. the Explain panel) has a stable, scope-correct identifier.
 */
export function crossBookResultToVaRResult(result: CrossBookVaRResultDto): VaRResultDto {
  return {
    bookId: result.portfolioGroupId,
    calculationType: result.calculationType,
    confidenceLevel: result.confidenceLevel,
    varValue: result.varValue,
    expectedShortfall: result.expectedShortfall,
    componentBreakdown: result.componentBreakdown,
    calculatedAt: result.calculatedAt,
  }
}
