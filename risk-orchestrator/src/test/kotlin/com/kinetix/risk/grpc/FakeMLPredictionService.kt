package com.kinetix.risk.grpc

import com.kinetix.proto.risk.MLPredictionServiceGrpcKt
import com.kinetix.proto.risk.RegimeDetectionRequest
import com.kinetix.proto.risk.RegimeDetectionResponse
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Test double for the gRPC `MLPredictionService`. Only [detectRegime] is wired —
 * the other RPCs inherit the base-class `UNIMPLEMENTED` defaults.
 *
 * Configure the regime classification result by supplying [detectRegimeHandler];
 * received requests are recorded in [detectRegimeRequests] for assertions.
 *
 * Bind to a real gRPC server via [GrpcFakeServer] so calls travel over real
 * HTTP/2 — interceptors, marshalling, and channel wiring are all exercised.
 */
class FakeMLPredictionService(
    var detectRegimeHandler: (RegimeDetectionRequest) -> RegimeDetectionResponse =
        { RegimeDetectionResponse.getDefaultInstance() },
) : MLPredictionServiceGrpcKt.MLPredictionServiceCoroutineImplBase() {

    val detectRegimeRequests: MutableList<RegimeDetectionRequest> = CopyOnWriteArrayList()

    override suspend fun detectRegime(request: RegimeDetectionRequest): RegimeDetectionResponse {
        detectRegimeRequests += request
        return detectRegimeHandler(request)
    }
}
