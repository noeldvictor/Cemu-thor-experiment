package info.cemu.cemu.emulation

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.WindowManager

class PadPresentation(
    context: Context,
    display: Display,
    private val rotateLeft: Boolean,
    private val holderCallback: SurfaceHolder.Callback,
    private val touchListener: CanvasOnTouchListener,
) : Presentation(context, display) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window?.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        )

        val mode = display.mode
        val (surfaceWidth, surfaceHeight) = computeSurfaceSize(
            width = mode.physicalWidth,
            height = mode.physicalHeight,
            rotateLeft = rotateLeft,
        )

        val surfaceView = SurfaceView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )

            holder.setFixedSize(surfaceWidth, surfaceHeight)
            holder.addCallback(holderCallback)
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    holder.surface.setFrameRate(
                        60f,
                        Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE
                    )
                }

                override fun surfaceChanged(
                    holder: SurfaceHolder,
                    format: Int,
                    width: Int,
                    height: Int,
                ) = Unit

                override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
            })
            setOnTouchListener(touchListener)
        }

        setContentView(surfaceView)
    }

    private fun computeSurfaceSize(width: Int, height: Int, rotateLeft: Boolean): Pair<Int, Int> {
        var surfaceWidth = width
        var surfaceHeight = height

        if (surfaceWidth < surfaceHeight) {
            val oldWidth = surfaceWidth
            surfaceWidth = surfaceHeight
            surfaceHeight = oldWidth
        }

        if (rotateLeft) {
            val oldWidth = surfaceWidth
            surfaceWidth = surfaceHeight
            surfaceHeight = oldWidth
        }

        return surfaceWidth to surfaceHeight
    }
}
