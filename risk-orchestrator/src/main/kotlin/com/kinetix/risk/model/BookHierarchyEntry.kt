package com.kinetix.risk.model

data class BookHierarchyEntry(
    val bookId: String,
    val deskId: String,
    val bookName: String?,
    val bookType: String?,
    val baseCurrency: String = "USD",
)
