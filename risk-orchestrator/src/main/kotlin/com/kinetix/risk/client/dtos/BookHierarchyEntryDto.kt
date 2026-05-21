package com.kinetix.risk.client.dtos

import com.kinetix.risk.model.BookHierarchyEntry
import kotlinx.serialization.Serializable

@Serializable
data class BookHierarchyEntryDto(
    val bookId: String,
    val deskId: String,
    val bookName: String? = null,
    val bookType: String? = null,
    val baseCurrency: String = "USD",
) {
    fun toDomain() = BookHierarchyEntry(
        bookId = bookId,
        deskId = deskId,
        bookName = bookName,
        bookType = bookType,
        baseCurrency = baseCurrency,
    )
}
