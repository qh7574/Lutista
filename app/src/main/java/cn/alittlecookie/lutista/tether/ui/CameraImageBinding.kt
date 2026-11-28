package cn.alittlecookie.lutista.tether.ui

import android.content.Context
import android.widget.ImageView
import cn.alittlecookie.lutista.tether.objects.CameraThumbKey
import cn.alittlecookie.lutista.tether.objects.cameraThumb
import coil.ImageLoader
import coil.request.Disposable
import coil.request.ImageRequest
import java.util.WeakHashMap

/** Coil owns decoded images; every request is bound to a precise camera session. */
class CameraImageBinding(
    private val context: Context,
    private val loader: ImageLoader,
    private val key: CameraThumbKey,
) : TetherImageSource {
    private val requests = WeakHashMap<ImageView, Disposable>()

    override val cacheIdentity: String = key.cacheKey

    override fun attach(view: ImageView) {
        requests.remove(view)?.dispose()
        requests[view] = loader.enqueue(
            ImageRequest.Builder(context).cameraThumb(key).target(view).build(),
        )
    }

    override fun detach(view: ImageView) {
        requests.remove(view)?.dispose()
        // 缩略图可以立刻清空，重建成本极低；清晰大图/内嵌预览保留上一帧，
        // 等新请求出图再替换，避免「清空 → 白屏 → 再出图」的闪烁。
        if (key.kind == CameraThumbKey.Kind.THUMB_160) view.setImageDrawable(null)
    }
}
