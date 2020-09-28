/*
 * Copyright 2019 New Vector Ltd
 * Copyright 2020 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.internal.session.room.send

import android.content.Context
import androidx.work.WorkerParameters
import com.nikitakozlov.pury.Pury
import com.nikitakozlov.pury.annotations.MethodProfiling
import com.nikitakozlov.pury.annotations.StartProfiling
import com.squareup.moshi.JsonClass
import io.realm.RealmConfiguration
import org.greenrobot.eventbus.EventBus
import org.matrix.android.sdk.api.failure.shouldBeRetried
import org.matrix.android.sdk.api.session.events.model.Content
import org.matrix.android.sdk.api.session.room.send.SendState
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.network.executeRequest
import org.matrix.android.sdk.internal.session.SessionComponent
import org.matrix.android.sdk.internal.session.room.RoomAPI
import org.matrix.android.sdk.internal.worker.SessionSafeCoroutineWorker
import org.matrix.android.sdk.internal.worker.SessionWorkerParams
import timber.log.Timber
import javax.inject.Inject

// private const val MAX_NUMBER_OF_RETRY_BEFORE_FAILING = 3

/**
 * Possible previous worker: [EncryptEventWorker] or first worker
 * Possible next worker    : None
 */
internal class SendEventWorker(context: Context,
                               params: WorkerParameters)
    : SessionSafeCoroutineWorker<SendEventWorker.Params>(context, params, Params::class.java) {

    @JsonClass(generateAdapter = true)
    internal data class Params(
            override val sessionId: String,
            override val lastFailureMessage: String? = null,
            val eventId: String
    ) : SessionWorkerParams

    @Inject lateinit var localEchoRepository: LocalEchoRepository
    @Inject lateinit var roomAPI: RoomAPI
    @Inject lateinit var eventBus: EventBus
    @Inject lateinit var cancelSendTracker: CancelSendTracker
    @SessionDatabase @Inject lateinit var realmConfiguration: RealmConfiguration

    override fun injectWith(injector: SessionComponent) {
        injector.inject(this)
    }

    override suspend fun doSafeWork(params: Params): Result {
        SendPerformanceTracker.startStage(params.eventId, SendPerformanceTracker.Stage.SEND_WORKER)
        val event = localEchoRepository.getUpToDateEcho(params.eventId)
        if (event?.eventId == null || event.roomId == null) {
            localEchoRepository.updateSendState(params.eventId, SendState.UNDELIVERED)
            return Result.success()
                    .also { Timber.e("Work cancelled due to bad input data") }
        }

        if (cancelSendTracker.isCancelRequestedFor(params.eventId, event.roomId)) {
            return Result.success()
                    .also {
                        cancelSendTracker.markCancelled(event.eventId, event.roomId)
                        Timber.e("## SendEvent: Event sending has been cancelled ${params.eventId}")
                    }
        }

        if (params.lastFailureMessage != null) {
            localEchoRepository.updateSendState(event.eventId, SendState.UNDELIVERED)
            // Transmit the error
            return Result.success(inputData)
                    .also { Timber.e("Work cancelled due to input error from parent") }
        }

        Timber.v("## SendEvent: [${System.currentTimeMillis()}] Send event ${params.eventId}")
        return try {
            sendEvent(event.eventId, event.roomId, event.type, event.content)
            Result.success()
        } catch (exception: Throwable) {
            if (/*currentAttemptCount >= MAX_NUMBER_OF_RETRY_BEFORE_FAILING ||**/ !exception.shouldBeRetried()) {
                Timber.e("## SendEvent: [${System.currentTimeMillis()}]  Send event Failed cannot retry ${params.eventId} > ${exception.localizedMessage}")
                localEchoRepository.updateSendState(event.eventId, SendState.UNDELIVERED)
                return Result.success()
            } else {
                Timber.e("## SendEvent: [${System.currentTimeMillis()}]  Send event Failed schedule retry ${params.eventId} > ${exception.localizedMessage}")
                Result.retry()
            }
        }
    }

    override fun buildErrorParams(params: Params, message: String): Params {
        return params.copy(lastFailureMessage = params.lastFailureMessage ?: message)
    }

    private suspend fun sendEvent(eventId: String, roomId: String, type: String, content: Content?) {
        localEchoRepository.updateSendState(eventId, SendState.SENDING)
        executeRequest<SendResponse>(eventBus) {
            apiCall = roomAPI.send(eventId, roomId, type, content)
        }
        localEchoRepository.updateSendState(eventId, SendState.SENT)
        SendPerformanceTracker.stopStage(eventId, SendPerformanceTracker.Stage.SEND_WORKER)
    }
}
