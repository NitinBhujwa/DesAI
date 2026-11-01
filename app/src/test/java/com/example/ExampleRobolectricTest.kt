package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.example.export.ScreenExporter
import com.example.export.TouchTransparentExportContainer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("UI Studio Core", appName)
  }

  // Item 13 Regression Test
  @Test
  fun `regression_item_13_verifyRenderedBitmapNotBlank_rejects_transparent_and_flat_bitmaps_and_accepts_rendered_content`() {
    // 1. Completely transparent bitmap -> rejected
    val transparentBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
    val resTransparent = ScreenExporter.verifyRenderedBitmapNotBlank(transparentBitmap, "#F8FAFC")
    assertTrue("Transparent bitmap must be rejected", resTransparent.isFailure)

    // 2. Single flat color unlike page background -> rejected
    val flatWrongColor = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
    flatWrongColor.eraseColor(Color.BLACK)
    val resFlatWrong = ScreenExporter.verifyRenderedBitmapNotBlank(flatWrongColor, "#F8FAFC")
    assertTrue("Single flat color unlike background must be rejected", resFlatWrong.isFailure)

    // 3. Valid rendered bitmap with background + foreground component pixels -> accepted
    val validRendered = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
    validRendered.eraseColor(Color.rgb(248, 250, 252))
    val accentColor = Color.rgb(79, 70, 229)
    for (y in 10 until 60) {
      for (x in 10 until 90) {
        validRendered.setPixel(x, y, accentColor)
      }
    }
    val resValid = ScreenExporter.verifyRenderedBitmapNotBlank(validRendered, "#F8FAFC")
    assertTrue("Bitmap with rendered UI elements must pass verification", resValid.isSuccess)
  }

  // Item 14 Regression Test
  @Test
  fun `regression_item_14_TouchTransparentExportContainer_does_not_consume_touches_and_hides_accessibility`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val container = TouchTransparentExportContainer(context)
    val now = SystemClock.uptimeMillis()
    val downEvent = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 50f, 50f, 0)
    try {
      assertFalse("Export container must not consume dispatchTouchEvent", container.dispatchTouchEvent(downEvent))
      assertFalse("Export container must not intercept touches", container.onInterceptTouchEvent(downEvent))
      assertFalse("Export container must not handle onTouchEvent", container.onTouchEvent(downEvent))
      assertEquals(
        View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
        container.importantForAccessibility,
      )
    } finally {
      downEvent.recycle()
    }
  }
}
