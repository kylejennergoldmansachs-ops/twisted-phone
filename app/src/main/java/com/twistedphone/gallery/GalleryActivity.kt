package com.twistedphone.gallery
import android.database.Cursor
import android.os.Bundle
import android.provider.MediaStore
import android.widget.GridView
import androidx.appcompat.app.AppCompatActivity
import androidx.loader.app.LoaderManager
import androidx.loader.content.CursorLoader
import androidx.loader.content.Loader
import com.twistedphone.R
import android.widget.SimpleCursorAdapter

class GalleryActivity : AppCompatActivity(), LoaderManager.LoaderCallbacks<Cursor> {
    private lateinit var grid: GridView
    private lateinit var adapter: SimpleCursorAdapter

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_gallery)
        grid = findViewById(R.id.galleryGrid)
        adapter = SimpleCursorAdapter(this, android.R.layout.simple_gallery_item, null, arrayOf(MediaStore.Images.Media.DATA), intArrayOf(android.R.id.text1), 0)
        grid.adapter = adapter
        LoaderManager.getInstance(this).initLoader(0, null, this)
    }

    override fun onCreateLoader(id: Int, args: Bundle?): Loader<Cursor> {
        return CursorLoader(this, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATA), null, null, null)
    }

    override fun onLoadFinished(loader: Loader<Cursor>, data: Cursor?) {
        adapter.swapCursor(data)
    }

    override fun onLoaderReset(loader: Loader<Cursor>) {
        adapter.swapCursor(null)
    }
}
