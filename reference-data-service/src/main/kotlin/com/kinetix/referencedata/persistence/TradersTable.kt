package com.kinetix.referencedata.persistence

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestampWithTimeZone

object TradersTable : Table("traders") {
    val id = varchar("id", 64)
    val name = varchar("name", 255)
    val deskId = varchar("desk_id", 255).references(DesksTable.id)
    val email = varchar("email", 255).nullable()
    val notionalLimitUsd = decimal("notional_limit_usd", 38, 4).nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(id)
}
