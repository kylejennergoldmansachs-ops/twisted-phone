package com.twistedphone.onboarding

import androidx.work.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import com.twistedphone.util.FileLogger

class ModelDownloadWorker(appContext: android.content.Context, params: WorkerParameters) : Worker(appContext, params) {
    override fun doWork(): Result {
        val token = inputData.getString("HUGGINGFACE_TOKEN") ?: ""
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
            
        val models = listOf(
            Pair("https://huggingface.co/qualcomm/Midas-V2/resolve/main/Midas-V2_w8a8.tflite", "midas.tflite"),
            Pair("https://huggingface.co/qualcomm/MediaPipe-Pose-Estimation/resolve/main/MediaPipe-Pose-Estimation_PoseLandmarkDetector.tflite", "pose.tflite")
        )
        val dir = File(applicationContext.filesDir, "models")
        dir.mkdirs()
        
        for ((url, name) in models) {
            var success = false
            var attempts = 0
            while (!success && attempts < 3) {
                try {
                    val requestBuilder = Request.Builder().url(url)
                    // Some HuggingFace models don't require authentication
                    if (token.isNotEmpty() && !url.contains("huggingface.co/qualcomm/")) {
                        requestBuilder.header("Authorization", "Bearer $token")
                    }
                    val req = requestBuilder.build()
                    client.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val body = resp.body?.byteStream() ?: return Result.failure()
                            File(dir, name).outputStream().use { body.copyTo(it) }
                            success = true
                            FileLogger.d(applicationContext, "ModelDownloadWorker", "Successfully downloaded $name")
                        } else {
                            attempts++
                            FileLogger.e(applicationContext, "ModelDownloadWorker", "Failed to download $name, HTTP ${resp.code}, attempt $attempts")
                            if (resp.code == 401 || resp.code == 403) {
                                FileLogger.e(applicationContext, "ModelDownloadWorker", "Authentication failed - check your HuggingFace token")
                            }
                            Thread.sleep(2000) // Wait longer before retry
                        }
                    }
                } catch (e: Exception) {
                    attempts++
                    FileLogger.e(applicationContext, "ModelDownloadWorker", "Error downloading $name: ${e.message}, attempt $attempts")
                    Thread.sleep(2000) // Wait longer before retry
                }
            }
            if (!success) {
                FileLogger.e(applicationContext, "ModelDownloadWorker", "Failed to download $name after 3 attempts")
                return Result.failure()
            }
        }
        FileLogger.d(applicationContext, "ModelDownloadWorker", "All models downloaded successfully")
        return Result.success()
    }
}
