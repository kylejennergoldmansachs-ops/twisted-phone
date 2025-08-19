package com.twistedphone.alt

import android.content.Intent
import android.os.Bundle
import android.util.Base64
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.twistedphone.TwistedApp
import java.io.File
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

class CameraCaptureActivity : AppCompatActivity() {
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Make the activity transparent
        window.setBackgroundDrawableResource(android.R.color.transparent)
        
        scope.launch {
            val result = captureThumbnail()
            
            // Send the result back via broadcast
            val intent = Intent("CAMERA_CAPTURE_RESULT")
            intent.putExtra("thumbnail", result)
            sendBroadcast(intent)
            
            finish()
        }
    }
    
    private suspend fun captureThumbnail(): String? = withContext(Dispatchers.IO) {
        try {
            val provider = ProcessCameraProvider.getInstance(this@CameraCaptureActivity).get(10, TimeUnit.SECONDS)
            val imageCapture = ImageCapture.Builder()
                .setTargetResolution(android.util.Size(128, 128))
                .build()
            
            provider.bindToLifecycle(this@CameraCaptureActivity, CameraSelector.DEFAULT_FRONT_CAMERA, imageCapture)
            var b64: String? = null
            val tempFile = File.createTempFile("thumbnail", ".jpg", cacheDir)
            val output = ImageCapture.OutputFileOptions.Builder(tempFile).build()
            
            val latch = java.util.concurrent.CountDownLatch(1)
            
            imageCapture.takePicture(output, ContextCompat.getMainExecutor(this@CameraCaptureActivity), 
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        val bmp = android.graphics.BitmapFactory.decodeFile(tempFile.absolutePath)
                        val baos = java.io.ByteArrayOutputStream()
                        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 50, baos)
                        b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                        tempFile.delete()
                        latch.countDown()
                    }
                    override fun onError(exc: ImageCaptureException) {
                        latch.countDown()
                    }
                })
            
            latch.await(5, TimeUnit.SECONDS)
            provider.unbindAll()
            b64
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
