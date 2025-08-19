package com.twistedphone.onboarding
import androidx.work.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

class ModelDownloadWorker(appContext: android.content.Context, params: WorkerParameters) : Worker(appContext, params) {
    override fun doWork(): Result {
        val token = inputData.getString("HUGGINGFACE_TOKEN") ?: ""
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
            
        val models = listOf(
            Pair("https://huggingface.co/qualcomm/Midas-V2-Quantized/resolve/main/Midas-V2.tflite", "midas.tflite"),
            Pair("https://huggingface.co/qualcomm/MediaPipe-Pose-Estimation/resolve/main/pose_landmarker.tflite", "pose.tflite")
        )
        val dir = File(applicationContext.filesDir, "models")
        dir.mkdirs()
        
        for ((url, name) in models) {
            var success = false
            var attempts = 0
            while (!success && attempts < 3) {
                try {
                    val requestBuilder = Request.Builder().url(url)
                    if (token.isNotEmpty()) {
                        requestBuilder.header("Authorization", "Bearer $token")
                    }
                    val req = requestBuilder.build()
                    client.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val body = resp.body?.byteStream() ?: return Result.failure()
                            File(dir, name).outputStream().use { body.copyTo(it) }
                            success = true
                        } else {
                            attempts++
                            Thread.sleep(1000) // Wait before retry
                        }
                    }
                } catch (e: Exception) {
                    attempts++
                    Thread.sleep(1000) // Wait before retry
                }
            }
            if (!success) {
                return Result.failure()
            }
        }
        return Result.success()
    }
}
