package com.kinetix.risk.client

import com.kinetix.common.model.Desk
import com.kinetix.common.model.DeskId
import com.kinetix.common.model.Division
import com.kinetix.common.model.DivisionId
import com.kinetix.risk.client.dtos.BookHierarchyEntryDto
import com.kinetix.risk.client.dtos.DeskDto
import com.kinetix.risk.client.dtos.DivisionDto
import com.kinetix.risk.model.BookHierarchyEntry
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import org.slf4j.LoggerFactory

/**
 * Fetches hierarchy data from reference-data-service (desks, divisions)
 * and position-service (book→desk mappings).
 */
class HttpHierarchyDataClient(
    private val httpClient: HttpClient,
    private val referenceDataBaseUrl: String,
    private val positionServiceBaseUrl: String,
) : HierarchyDataClient {

    private val logger = LoggerFactory.getLogger(HttpHierarchyDataClient::class.java)

    override suspend fun getAllDivisions(): List<Division> {
        return try {
            val dtos: List<DivisionDto> = httpClient.get("$referenceDataBaseUrl/api/v1/divisions").body()
            dtos.map { it.toDomain() }
        } catch (e: Exception) {
            logger.error("Failed to fetch divisions from reference-data-service", e)
            emptyList()
        }
    }

    override suspend fun getDesksByDivision(divisionId: DivisionId): List<Desk> {
        return try {
            val dtos: List<DeskDto> = httpClient
                .get("$referenceDataBaseUrl/api/v1/desks?divisionId=${divisionId.value}")
                .body()
            dtos.map { it.toDomain() }
        } catch (e: Exception) {
            logger.error("Failed to fetch desks for division {} from reference-data-service", divisionId.value, e)
            emptyList()
        }
    }

    override suspend fun getAllDesks(): List<Desk> {
        return try {
            val dtos: List<DeskDto> = httpClient.get("$referenceDataBaseUrl/api/v1/desks").body()
            dtos.map { it.toDomain() }
        } catch (e: Exception) {
            logger.error("Failed to fetch desks from reference-data-service", e)
            emptyList()
        }
    }

    override suspend fun getAllBookMappings(): List<BookHierarchyEntry> {
        return try {
            val dtos: List<BookHierarchyEntryDto> = httpClient
                .get("$positionServiceBaseUrl/api/v1/book-hierarchy")
                .body()
            dtos.map { it.toDomain() }
        } catch (e: Exception) {
            logger.error("Failed to fetch book-hierarchy mappings from position-service", e)
            emptyList()
        }
    }

    override suspend fun getBookMapping(bookId: String): BookHierarchyEntry? {
        return try {
            val response = httpClient.get("$positionServiceBaseUrl/api/v1/book-hierarchy/$bookId")
            if (response.status == HttpStatusCode.NotFound) {
                null
            } else {
                response.body<BookHierarchyEntryDto>().toDomain()
            }
        } catch (e: Exception) {
            logger.error("Failed to fetch book-hierarchy mapping for book {} from position-service", bookId, e)
            null
        }
    }
}
