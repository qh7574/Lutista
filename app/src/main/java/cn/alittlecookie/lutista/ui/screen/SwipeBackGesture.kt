package cn.alittlecookie.lutista.ui.screen

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/** 触发返回所需的最小水平位移。取 64dp，比系统返回手势的阈值略松，跟手又不至于误触。 */
private val DirtyBackSwipeThreshold = 64.dp

/**
 * 「有未保存修改时」的侧滑返回手势。
 *
 * 背景：编辑器页在干净状态下由 miuix-nav 的 `swipeDismiss` 提供侧滑关闭（带跟手位移与
 * MiuixDefault 的物理回弹）。一旦有未保存修改，外层会把 `swipeDismiss` 设为 `None`：
 * miui 的手势提交只调 `NavDisplay.onBack`，不经过页面内的「保存更改?」守卫，若在
 * `onBack` 里「只弹框不 pop」，手势的深度驱动没有新 target 可收敛，页面会永久卡在半滑出
 * 的位置（真机实测）。所以脏状态下改由本手势接管。
 *
 * 与本手势配合的两个关键点：
 * 1. 命中后走页面自己的 `requestBack()`，因此三种返回路径（返回箭头 / 系统返回键 / 侧滑）
 *    落点完全一致 —— 有未保存修改一律先弹「保存更改?」。
 * 2. 拖动期间 `consume()`，避免手势穿透到下层可点击区域（例如预览图的「点击更换示例图」
 *    会把一次侧滑当成点击，弹出文件选择器）。
 *
 * 因为挂在页面最外层，内部的滑块 / Tab / 按钮仍然是子孙节点，会先在 Main pass 消费事件，
 * 本手势检测器随即取消，不会抢走它们的横向拖动。
 *
 * @param enabled 仅在「有未保存修改」时为 true；干净状态返回 [Modifier]（交给 miui 的手势）。
 * @param onBack 手势命中后执行的返回入口，通常是页面的 `requestBack()`。
 */
@Composable
fun rememberDirtyBackSwipeModifier(enabled: Boolean, onBack: () -> Unit): Modifier {
    // rememberUpdatedState：Modifier 只在 enabled 翻转时重建（避免拖动途中被重组打断），
    // 但回调始终读最新值，不会持有过期 lambda。
    val currentOnBack = rememberUpdatedState(onBack)
    return remember(enabled) {
        if (!enabled) {
            Modifier
        } else {
            Modifier.pointerInput(Unit) {
                var draggedPx = 0f
                detectHorizontalDragGestures(
                    onDragStart = { draggedPx = 0f },
                    onDragCancel = { draggedPx = 0f },
                    onDragEnd = {
                        if (draggedPx > DirtyBackSwipeThreshold.toPx()) {
                            currentOnBack.value()
                        }
                        draggedPx = 0f
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        draggedPx += dragAmount
                    }
                )
            }
        }
    }
}
