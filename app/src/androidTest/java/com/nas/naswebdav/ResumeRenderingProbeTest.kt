package com.nas.naswebdav

import android.content.Intent
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.lifecycle.ViewModelProvider
import com.nas.naswebdav.browser.FileBrowserViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ResumeRenderingProbeTest {
    private fun painted(bitmap: Bitmap, top: Double=0.915, bottom: Double=0.97): Int {
        var painted=0
        for(y in (bitmap.height*top).toInt() until (bitmap.height*bottom).toInt() step 4) {
            for(x in 0 until bitmap.width step 8) {
                val color=bitmap.getPixel(x,y)
                val r=(color shr 16) and 255;val g=(color shr 8) and 255;val b=color and 255
                if(r+g+b>180) painted++
            }
        }
        return painted
    }

    @Test(timeout=120000) fun navigationRemainsPaintedAfterBackgroundResume() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        fun score(top: Double=0.915,bottom: Double=0.97)=instrumentation.uiAutomation.takeScreenshot().let {bitmap ->
            try {painted(bitmap,top,bottom)} finally {bitmap.recycle()}
        }
        fun nasRoot() = instrumentation.uiAutomation.rootInActiveWindow?.takeIf {
            it.packageName?.toString() == context.packageName
        }
        fun hasNavigation(root: AccessibilityNodeInfo): Boolean {
            val labels = mutableSetOf<String>()
            fun visit(node: AccessibilityNodeInfo) {
                node.text?.let { labels.add(it.toString()) }
                node.contentDescription?.let { labels.add(it.toString()) }
                for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
            }
            visit(root)
            return "Trang chủ" in labels && "Tệp" in labels
        }
        fun shell(command: String) {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use {it.readBytes()}
        }
        var before=0
        val deadline=SystemClock.uptimeMillis()+45000
        while(before<200 && SystemClock.uptimeMillis()<deadline) {
            SystemClock.sleep(1000)
            before=if(nasRoot()?.let(::hasNavigation) == true) score() else 0
        }
        assumeTrue("NAS is not the active window", nasRoot() != null)
        assertTrue("Initial navigation did not paint: $before",before>=200)
        val beforeContent=score(0.35,0.75)
        var folder=""
        instrumentation.runOnMainSync {folder=ViewModelProvider(activity)[FileBrowserViewModel::class.java].currentUrl}
        shell("input keyevent KEYCODE_HOME")
        SystemClock.sleep(20000)
        shell("am start -f 0x10020000 -n com.nas.naswebdav/.MainActivity")
        SystemClock.sleep(2000)
        val resumedRoot = nasRoot()
        assumeTrue("Another surface covers NAS after resume", resumedRoot != null)
        assertTrue("Navigation missing after resume", hasNavigation(requireNotNull(resumedRoot)))
        instrumentation.runOnMainSync {
            val resumedActivity=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>().singleOrNull()
            assertSame("Resume recreated the Activity",activity,resumedActivity)
            assertEquals(folder,ViewModelProvider(activity)[FileBrowserViewModel::class.java].currentUrl)
        }
        val resumed=score()
        val resumedContent=score(0.35,0.75)
        val detail="before=$before resumed=$resumed content=$beforeContent/$resumedContent"
        println("NAV_PAINT $detail")
        assertTrue("Navigation lost on resume: $detail",resumed>=before/2)
        assertTrue("Content lost on resume: before=$beforeContent resumed=$resumedContent",resumedContent>=beforeContent/2)
    }
}
