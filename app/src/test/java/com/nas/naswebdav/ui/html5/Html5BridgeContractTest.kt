package com.nas.naswebdav.ui.html5

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Html5BridgeContractTest {

    private fun readProjectFile(relativePath: String): String {
        val candidates = listOf(
            File(relativePath),
            File("..", relativePath)
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("Cannot find $relativePath from ${File(".").absolutePath}")
        return file.readText(Charsets.UTF_8)
    }

    @Test
    fun allJsCallBridgeMethodsExistInKotlinJavascriptInterface() {
        val js = readProjectFile("app/src/main/assets/html5/app.js")
        val kt = readProjectFile("app/src/main/java/com/nas/naswebdav/ui/html5/NasHtmlDashboard.kt")

        val jsCalls = Regex("""callBridge\(\s*["']([a-zA-Z0-9_]+)["']""")
            .findAll(js)
            .map { it.groupValues[1] }
            .toSet()

        val ktMethods = Regex("""@JavascriptInterface\s+fun\s+([a-zA-Z0-9_]+)\s*\(""")
            .findAll(kt)
            .map { it.groupValues[1] }
            .toSet()

        val missing = jsCalls - ktMethods
        assertTrue(
            "JS callBridge() methods missing @JavascriptInterface in NasHtmlDashboard.kt: $missing",
            missing.isEmpty()
        )
    }

    @Test
    fun allHtmlDataActionsAndToolboxActionsAreHandled() {
        val html = readProjectFile("app/src/main/assets/html5/index.html")
        val js = readProjectFile("app/src/main/assets/html5/app.js")
        val nav = readProjectFile("app/src/main/java/com/nas/naswebdav/ui/navigation/NasNavGraph.kt")

        val htmlActions = Regex("""data-(?:action|tbact)="([^"]+)"""")
            .findAll(html)
            .map { it.groupValues[1] }
            .toSet()

        val unhandled = htmlActions.filter { act ->
            val inJs = js.contains("\"$act\"")
            val inNav = nav.contains("\"$act\"")
            !inJs && !inNav
        }
        assertTrue("Unhandled HTML data-action/data-tbact: $unhandled", unhandled.isEmpty())
    }

    @Test
    fun dashboardContainsBackgroundTasksPanelAndFocusGuard() {
        val html = readProjectFile("app/src/main/assets/html5/index.html")
        val js = readProjectFile("app/src/main/assets/html5/app.js")

        assertTrue("index.html must include #bgTasksCard on dashboard", html.contains("id=\"bgTasksCard\""))
        assertTrue("app.js must define renderBgTasks()", js.contains("function renderBgTasks()"))
        assertTrue(
            "renderSubUiModal must guard focused inputs during auto-refresh",
            js.contains("document.activeElement") && js.contains("bodyEl.contains(ae)")
        )
    }
}
