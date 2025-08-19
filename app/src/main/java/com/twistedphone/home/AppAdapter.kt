package com.twistedphone.home
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import android.widget.LinearLayout
import android.content.Intent

class AppAdapter(private val ctx: Context, private val apps: List<Pair<String, Intent>>) : BaseAdapter() {
    override fun getCount() = apps.size
    override fun getItem(p: Int) = apps[p]
    override fun getItemId(p: Int) = p.toLong()
    override fun getView(pos: Int, convertView: View?, parent: ViewGroup?): View {
        val tv = (convertView as? TextView) ?: TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(400,400)
            textSize=18f
            setPadding(16,16,16,16)
            setTextColor(0xFFFFFFFF.toInt())
        }
        tv.text = apps[pos].first
        return tv
    }
}
