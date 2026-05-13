package com.kinetix.common.model

data class TraderId(val value: String) {
    init {
        require(value.isNotBlank()) { "TraderId must not be blank" }
    }
}
