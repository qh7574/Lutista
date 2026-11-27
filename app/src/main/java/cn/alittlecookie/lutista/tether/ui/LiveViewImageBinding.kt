package cn.alittlecookie.lutista.tether.ui

import android.widget.ImageView
import cn.alittlecookie.lutista.tether.render.RenderedFrame
import java.lang.ref.WeakReference

/** Main-thread display ownership. Rebinding a view never changes the camera stream. */
class LiveViewImageBinding(
    private val onFramePresented: (RenderedFrame) -> Unit = {},
) : TetherImageSource, AutoCloseable {
    private val views = mutableListOf<WeakReference<ImageView>>()
    private var current: RenderedFrame? = null
    private var presented: RenderedFrame? = null

    private fun recordPresentation(frame: RenderedFrame) {
        if (presented !== frame) { presented = frame; onFramePresented(frame) }
    }

    override fun attach(view: ImageView) {
        views.removeAll { it.get() == null || it.get() === view }
        views += WeakReference(view)
        view.setImageBitmap(current?.bitmap)
        current?.let(::recordPresentation)
    }

    override fun detach(view: ImageView) {
        views.removeAll { it.get() == null || it.get() === view }
        view.setImageDrawable(null)
    }

    fun offer(frame: RenderedFrame) {
        val previous = current
        current = frame
        views.removeAll { it.get() == null }
        views.forEach { it.get()?.setImageBitmap(frame.bitmap) }
        if (views.any { it.get() != null }) recordPresentation(frame)
        // The old bitmap is no longer referenced by an ImageView before it is recycled.
        if (previous !== frame) previous?.close()
    }

    override fun close() {
        views.forEach { it.get()?.setImageDrawable(null) }
        views.clear()
        current?.close()
        current = null
        presented = null
    }
}
