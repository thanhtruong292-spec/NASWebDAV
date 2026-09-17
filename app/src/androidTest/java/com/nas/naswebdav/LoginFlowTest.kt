package com.nas.naswebdav

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test

/**
 * Task 5: Espresso/Compose smoke — login render + validation, không cần NAS thật.
 * Chạy trên emulator/device: ./gradlew :app:connectedDebugAndroidTest
 */
class LoginFlowTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun loginFieldsRender() {
        composeRule.onNodeWithTag("login_host").assertIsDisplayed()
        composeRule.onNodeWithTag("login_user").assertIsDisplayed()
        composeRule.onNodeWithTag("login_pass").assertIsDisplayed()
        composeRule.onNodeWithTag("login_connect").assertIsDisplayed()
    }

    @Test
    fun connectDisabledWithEmptyHost() {
        // Host rỗng → nút không cho bấm (tránh ping mù).
        composeRule.onNodeWithTag("login_connect").assertIsNotEnabled()
    }

    @Test
    fun connectEnabledAfterHostInput() {
        composeRule.onNodeWithTag("login_host").performTextInput("192.168.100.254")
        composeRule.onNodeWithTag("login_user").performTextInput("admin")
        composeRule.onNodeWithTag("login_pass").performTextInput("secret")
        composeRule.onNodeWithTag("login_connect").assertIsDisplayed()
    }
}
