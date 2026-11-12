package com.szdict.app.feature.ocr

import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import com.szdict.app.platform.runOnMain
import platform.Foundation.NSItemProvider
import platform.UniformTypeIdentifiers.UTTypeImage

/** The item providers of the dragged items that can deliver an image (a file or in-memory image, never a web URL: GLB-090). */
@OptIn(ExperimentalComposeUiApi::class)
private fun DragAndDropEvent.imageProviders(): List<NSItemProvider> =
    items.map { it.itemProvider }.filter { it.hasItemConformingToTypeIdentifier(UTTypeImage.identifier) }

/** CAM-131: images dragged from Photos, Files or another app in Split View or Slide Over (Compose's `UIDropInteraction`). */
@Composable
actual fun Modifier.imageDropTarget(onHover: (Boolean) -> Unit, onDrop: (String?) -> Unit): Modifier {
    val hover by rememberUpdatedState(onHover)
    val drop by rememberUpdatedState(onDrop)
    val target = remember {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) = hover(event.imageProviders().isNotEmpty())
            override fun onExited(event: DragAndDropEvent) = hover(false)
            override fun onEnded(event: DragAndDropEvent) = hover(false)

            override fun onDrop(event: DragAndDropEvent): Boolean {
                hover(false)
                val provider = event.imageProviders().firstOrNull()
                if (provider == null) {
                    drop(null)
                } else {
                    // The encoded image as the source app provides it, with its orientation metadata.
                    provider.loadDataRepresentationForTypeIdentifier(UTTypeImage.identifier) { data, _ ->
                        runOnMain { drop(data?.let(IosImages::put)) }
                    }
                }
                return true
            }
        }
    }
    return dragAndDropTarget(shouldStartDragAndDrop = { true }, target = target)
}
