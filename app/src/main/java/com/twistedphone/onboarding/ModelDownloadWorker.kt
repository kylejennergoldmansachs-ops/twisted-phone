package com.twistedphone.onboarding
import androidx.work.Worker
import androidx.work.WorkerParameters
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

class ModelDownloadWorker(appContext: android.content.Context, params: WorkerParameters) : Worker(appContext, params) {
    override fun doWork(): Result {
        val client = OkHttpClient()
        val models = listOf(
            Pair("https://huggingface.co/qualcomm/Midas-V2-Quantized/resolve/main/Midas-V2.tflite", "midas.tflite"),
            Pair("https://huggingface.co/qualcomm/MediaPipe-Pose-Estimation/resolve/main/pose_landmarker.tflite", "pose.tflite")
        )
        val dir = File(applicationContext.filesDir, "models")
        dir.mkdirs()
        for ((url, name) in models) {
            val req = Request.Builder().url(url).build()
            try {
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.byteStream() ?: return Result.failure()
                        File(dir, name).outputStream().use { body.copyTo(it) }
                    } else return Result.failure()
                }
            } catch (e: Exception) { return Result.failure() }
        }
        return Result.success()
    }
}
