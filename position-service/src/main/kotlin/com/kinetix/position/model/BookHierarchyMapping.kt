package com.kinetix.position.model

data class BookHierarchyMapping(
    val bookId: String,
    val deskId: String,
    val bookName: String?,
    val bookType: String?,
    val baseCurrency: String = "USD",
)
