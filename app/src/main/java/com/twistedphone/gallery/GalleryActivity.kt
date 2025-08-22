package com.twistedphone.gallery

import android.content.ContentUris
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import com.twistedphone.R
import com.twistedphone.util.FileLogger
import kotlinx.coroutines.*
import java.lang.ref.WeakReference

class GalleryActivity : AppCompatActivity() {
    private lateinit var grid: GridView
    private lateinit var adapter: ImageAdapter
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)
        grid = findViewById(R.id.galleryGrid)
        adapter = ImageAdapter()
        grid.adapter = adapter
        loadImages()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun loadImages() {
        scope.launch {
            val images = withContext(Dispatchers.IO) {
                val out = mutableListOf<Long>()
                val resolver = contentResolver
                val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED)
                val sort = "${MediaStore.Images.Media.DATE_ADDED} DESC"
                resolver.query(uri, projection, null, null, sort)?.use { cursor ->
                    val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idIndex)
                        out.add(id)
                        if (out.size >= 200) break // limit for UI responsiveness
                    }
                }
                out
            }
            adapter.setIds(images)
        }
    }

    inner class ImageAdapter : BaseAdapter() {
        private val ids = mutableListOf<Long>()
        fun setIds(list: List<Long>) {
            ids.clear()
            ids.addAll(list)
            notifyDataSetChanged()
        }
        override fun getCount(): Int = ids.size
        override fun getItem(position: Int): Any = ids[position]
        override fun getItemId(position: Int): Long = ids[position]
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val iv = (convertView as? ImageView) ?: ImageView(parent.context).apply {
                layoutParams = GridView.LayoutParams(300, 300)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            val id = ids[position]
            // load thumbnail asynchronously
            val weak = WeakReference(iv)
            scope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    try {
                        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                        // request a thumbnail-sized bitmap
                        contentResolver.openInputStream(uri)?.use { stream ->
                            val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
                            return@withContext android.graphics.BitmapFactory.decodeStream(stream, null, options)
                        }
                    } catch (e: Exception) {
                        FileLogger.e(this@GalleryActivity, "GalleryActivity", "thumbnail load failed: ${e.message}")
                        null
                    }
                }
                val imageView = weak.get()
                if (imageView != null && bitmap != null) {
                    imageView.setImageBitmap(bitmap)
                }
            }
            return iv
        }
    }
}

