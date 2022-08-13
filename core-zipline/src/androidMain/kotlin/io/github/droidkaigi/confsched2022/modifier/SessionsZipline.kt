package io.github.droidkaigi.confsched2022.modifier

import android.app.Application
import app.cash.zipline.EventListener
import app.cash.zipline.Zipline
import app.cash.zipline.loader.ZiplineLoader
import co.touchlab.kermit.Logger
import io.github.droidkaigi.confsched2022.model.Timetable
import java.util.concurrent.Executors
import javax.inject.Inject
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class SessionsZipline @Inject constructor(
    context: Application,
    okHttpClient: OkHttpClient
) {
    private val executorService = Executors.newSingleThreadExecutor { Thread(it, "Zipline") }
    private val dispatcher = executorService.asCoroutineDispatcher()

    private val manifestUrl = "http://10.0.2.2:8080/manifest.zipline.json"

    private val ziplineLoader = ZiplineLoader(
        context = context,
        dispatcher = dispatcher,
        httpClient = okHttpClient,
        eventListener = object : EventListener() {
            override fun manifestParseFailed(
                applicationName: String,
                url: String?,
                exception: Exception
            ) {
                Logger.d(exception) { "Zipline manifestParseFailed" }
            }

            override fun applicationLoadFailed(
                applicationName: String,
                manifestUrl: String?,
                exception: Exception
            ) {
                Logger.d(exception) { "Zipline applicationLoadFailed" }
            }

            override fun downloadFailed(
                applicationName: String,
                url: String,
                exception: Exception
            ) {
                Logger.d(exception) { "Zipline downloadFailed" }
            }
        },
        nowEpochMs = { System.currentTimeMillis() }
    )

    fun timetableModifier(
        coroutineScope: CoroutineScope,
        initialTimetable: Timetable,
        timetableFlow: Flow<Timetable>
    ): StateFlow<Timetable> {
        val modelStateFlow = MutableStateFlow(initialTimetable)
        coroutineScope.launch(dispatcher) {
            var zipline: Zipline? = null
            val presenter = try {
                val loadedZiplineFlow = ziplineLoader.load("timeline", flowOf(manifestUrl), { })
                loadedZiplineFlow.catch { throwable -> throwable.printStackTrace() }
                val loadedZipline = loadedZiplineFlow.firstOrNull()
                if (loadedZipline == null) {
                    loadedZiplineFlow.catch { it.printStackTrace() }
                }
                zipline = loadedZipline!!.zipline
                zipline.take<TimetableModifier>("sessionsModifier")
            } catch (e: Exception) {
                Logger.d(e) { "zipline load error" }
                object : TimetableModifier {
                    override suspend fun produceModels(timetable: Timetable): Timetable {
                        return timetable
                    }
                }
            }
            val stateFlow =
                MutableStateFlow(initialTimetable)
            launch {
                timetableFlow.collect {
                    val produceModels = presenter.produceModels(it)
                    stateFlow.value = produceModels
                }
            }

            modelStateFlow.emitAll(stateFlow)

            coroutineContext.job.invokeOnCompletion {
                dispatcher.dispatch(EmptyCoroutineContext) { zipline?.close() }
                executorService.shutdown()
            }
        }
        return modelStateFlow
    }
}