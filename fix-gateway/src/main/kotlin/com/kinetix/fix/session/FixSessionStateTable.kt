package com.kinetix.fix.session

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestampWithTimeZone

object FixSessionStateTable : Table("fix_session_state") {
    val venue = varchar("venue", 32)
    val senderSeqNum = long("sender_seq_num")
    val targetSeqNum = long("target_seq_num")
    val lastLogonAt = timestampWithTimeZone("last_logon_at").nullable()
    val lastLogoutAt = timestampWithTimeZone("last_logout_at").nullable()
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(venue)
}
