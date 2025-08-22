package com.twistedphone.onboarding

import androidx.work.Worker
import androidx.work.WorkerParameters
import okhttp3.OkHttpClient
import okhttp3.Request
import com.twistedphone.util.FileLogger
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Updated ModelDownloadWorker.
 *
 * - Keeps the original retry logic (3 attempts)
 * - Adds MobileSAM encoder+decoder downloads (encoder + decoder URLs provided by you)
 * - Writes to temporary file then renames to final filename to avoid partial-file problems
 * - Logs successes, HTTP failures and exceptions via FileLogger
 * - Uses the provided HUGGINGFACE_TOKEN (if any) for Authorization header
 */
class ModelDownloadWorker(appContext: android.content.Context, params: WorkerParameters) : Worker(appContext, params) {
    override fun doWork(): Result {
        val token = inputData.getString("HUGGINGFACE_TOKEN") ?: ""
        val client = OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        // Models to download: (url, target filename)
        val models = listOf(
            Pair("https://huggingface.co/qualcomm/Midas-V2/resolve/main/Midas-V2_w8a8.tflite", "midas.tflite"),
            Pair("https://huggingface.co/qualcomm/MediaPipe-Pose-Estimation/resolve/main/MediaPipe-Pose-Estimation_PoseLandmarkDetector.tflite", "pose.tflite"),
            // MobileSAM encoder + decoder (exact links you provided)
            Pair("https://huggingface.co/qualcomm/MobileSam/resolve/main/MobileSam_MobileSAMEncoder.tflite", "MobileSam_MobileSAMEncoder.tflite"),
            Pair("https://huggingface.co/qualcomm/MobileSam/resolve/main/MobileSam_MobileSAMDecoder.tflite", "MobileSam_MobileSAMDecoder.tflite")
        )

        val dir = File(applicationContext.filesDir, "models")
        if (!dir.exists()) dir.mkdirs()

        for ((url, name) in models) {
            var success = false
            var attempts = 0
            while (!success && attempts < 3) {
                attempts++
                var respBodyStream: InputStream? = null
                var tmpFile: File? = null
                try {
                    val rb = Request.Builder().url(url)
                    if (token.isNotEmpty()) {
                        // If user supplied a HuggingFace token, send it for authenticated downloads.
                        rb.header("Authorization", "Bearer $token")
                    }
                    val req = rb.build()
                    val resp = client.newCall(req).execute()
                    resp.use { response ->
                        if (!response.isSuccessful) {
                            FileLogger.e(applicationContext, "ModelDownloadWorker", "Failed to download $name, HTTP ${response.code}, attempt $attempts")
                            // If token authentication error, abort early - token likely invalid
                            if (response.code == 401 || response.code == 403) {
                                FileLogger.e(applicationContext, "ModelDownloadWorker", "Authentication failed for $url (HTTP ${response.code}). Check HuggingFace token.")
                                return Result.failure()
                            }
                            // otherwise retry after brief sleep
                            Thread.sleep((1500L * attempts).coerceAtMost(6000L))
                        } else {
                            respBodyStream = response.body?.byteStream()
                            if (respBodyStream == null) {
                                FileLogger.e(applicationContext, "ModelDownloadWorker", "Empty body when downloading $name, attempt $attempts")
                                Thread.sleep((1500L * attempts).coerceAtMost(6000L))
                            } else {
                                // write to temp file then rename atomically
                                tmpFile = File(dir, "$name.tmp")
                                FileOutputStream(tmpFile).use { out ->
                                    val buf = ByteArray(8192)
                                    var read = respBodyStream.read(buf)
                                    var written = 0L
                                    while (read >= 0) {
                                        out.write(buf, 0, read)
                                        written += read
                                        read = respBodyStream.read(buf)
                                    }
                                    out.flush()
                                    out.fd.sync()
                                }
                                // basic sanity: file > 100 bytes
                                if (tmpFile.exists() && tmpFile.length() > 100) {
                                    val finalFile = File(dir, name)
                                    if (finalFile.exists()) finalFile.delete()
                                    val renamed = tmpFile.renameTo(finalFile)
                                    if (!renamed) {
                                        // fallback: copy then delete tmp
                                        tmpFile.copyTo(finalFile, overwrite = true)
                                        tmpFile.delete()
                                    }
                                    FileLogger.d(applicationContext, "ModelDownloadWorker", "Successfully downloaded $name (attempt $attempts)")
                                    success = true
                                } else {
                                    FileLogger.e(applicationContext, "ModelDownloadWorker", "Downloaded file too small or missing for $name (attempt $attempts)")
                                    tmpFile?.delete()
                                    Thread.sleep((1500L * attempts).coerceAtMost(6000L))
                                }
                            }
                        }
                    }
                } catch (ex: Exception) {
                    FileLogger.e(applicationContext, "ModelDownloadWorker", "Error downloading $name: ${ex.message}, attempt $attempts")
                    try { tmpFile?.delete() } catch (_: Exception) {}
                    try { respBodyStream?.close() } catch (_: Exception) {}
                    Thread.sleep((1500L * attempts).coerceAtMost(6000L))
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
