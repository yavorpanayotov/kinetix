package com.kinetix.referencedata.grpc

import com.kinetix.common.model.DeskId
import com.kinetix.common.model.Trader
import com.kinetix.common.model.TraderId
import com.kinetix.proto.referencedata.GetTraderRequest
import com.kinetix.proto.referencedata.GetTraderResponse
import com.kinetix.proto.referencedata.ListTradersForDeskRequest
import com.kinetix.proto.referencedata.ListTradersForDeskResponse
import com.kinetix.referencedata.persistence.DeskRepository
import com.kinetix.referencedata.persistence.TraderRepository
import com.kinetix.referencedata.service.TraderService
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.stub.StreamObserver
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.math.BigDecimal

private class RecordingObserver<T> : StreamObserver<T> {
    val values = mutableListOf<T>()
    var error: Throwable? = null
    var completed = false
    override fun onNext(value: T) { values += value }
    override fun onError(t: Throwable) { error = t }
    override fun onCompleted() { completed = true }
}

private class InMemoryTraderRepository : TraderRepository {
    private val byId = mutableMapOf<String, Trader>()
    override suspend fun save(trader: Trader) { byId[trader.id.value] = trader }
    override suspend fun findById(id: TraderId): Trader? = byId[id.value]
    override suspend fun findAll(): List<Trader> = byId.values.toList()
    override suspend fun findByDeskId(deskId: DeskId): List<Trader> =
        byId.values.filter { it.deskId == deskId }
}

private class AlwaysFindsDeskRepository : DeskRepository {
    override suspend fun save(desk: com.kinetix.common.model.Desk) {}
    override suspend fun findById(id: DeskId) =
        com.kinetix.common.model.Desk(
            id = id,
            name = "${id.value} Desk",
            divisionId = com.kinetix.common.model.DivisionId("equities"),
        )
    override suspend fun findAll() = emptyList<com.kinetix.common.model.Desk>()
    override suspend fun findByDivisionId(divisionId: com.kinetix.common.model.DivisionId) =
        emptyList<com.kinetix.common.model.Desk>()
}

class TraderLookupServiceImplTest : FunSpec({

    val deskRepository = AlwaysFindsDeskRepository()
    val traderRepository = InMemoryTraderRepository()
    val traderService = TraderService(traderRepository, deskRepository)
    val impl = TraderLookupServiceImpl(traderService)

    suspend fun seed(id: String, deskId: String, name: String = "Trader $id"): Trader {
        val t = Trader(
            id = TraderId(id),
            name = name,
            deskId = DeskId(deskId),
            email = "$id@kinetix.test",
            notionalLimitUsd = BigDecimal("100000000"),
        )
        traderService.create(t)
        return t
    }

    test("getTrader returns the trader when present") {
        seed("tr-eg-001", deskId = "equity-growth", name = "Sarah Chen")
        val observer = RecordingObserver<GetTraderResponse>()

        impl.getTrader(GetTraderRequest.newBuilder().setTraderId("tr-eg-001").build(), observer)

        observer.error shouldBe null
        observer.completed shouldBe true
        observer.values.size shouldBe 1
        with(observer.values.single()) {
            id shouldBe "tr-eg-001"
            name shouldBe "Sarah Chen"
            deskId shouldBe "equity-growth"
            email shouldBe "tr-eg-001@kinetix.test"
            notionalLimitUsd shouldBe "100000000"
        }
    }

    test("getTrader returns NOT_FOUND for an unknown trader id") {
        val observer = RecordingObserver<GetTraderResponse>()
        impl.getTrader(GetTraderRequest.newBuilder().setTraderId("missing").build(), observer)

        observer.values shouldBe emptyList()
        observer.completed shouldBe false
        observer.error.shouldBeInstanceOf<StatusRuntimeException>()
        (observer.error as StatusRuntimeException).status.code shouldBe Status.Code.NOT_FOUND
    }

    test("getTrader returns INVALID_ARGUMENT for a blank id") {
        val observer = RecordingObserver<GetTraderResponse>()
        impl.getTrader(GetTraderRequest.newBuilder().setTraderId("").build(), observer)

        (observer.error as StatusRuntimeException).status.code shouldBe Status.Code.INVALID_ARGUMENT
    }

    test("listTradersForDesk returns every trader on the desk") {
        seed("tr-eg-001", deskId = "equity-growth")
        seed("tr-eg-002", deskId = "equity-growth")
        seed("tr-tm-001", deskId = "tech-momentum")
        val observer = RecordingObserver<ListTradersForDeskResponse>()

        impl.listTradersForDesk(
            ListTradersForDeskRequest.newBuilder().setDeskId("equity-growth").build(),
            observer,
        )

        observer.error shouldBe null
        observer.completed shouldBe true
        val ids = observer.values.single().tradersList.map { it.id }.toSet()
        ids shouldBe setOf("tr-eg-001", "tr-eg-002")
    }

    test("listTradersForDesk returns INVALID_ARGUMENT for a blank deskId") {
        val observer = RecordingObserver<ListTradersForDeskResponse>()
        impl.listTradersForDesk(
            ListTradersForDeskRequest.newBuilder().setDeskId("").build(),
            observer,
        )
        (observer.error as StatusRuntimeException).status.code shouldBe Status.Code.INVALID_ARGUMENT
    }
})
