package com.nas.naswebdav

import androidx.test.core.app.ApplicationProvider
import com.nas.naswebdav.auth.AuthSessionViewModel
import com.nas.naswebdav.shared.ConnectionStatus
import com.nas.naswebdav.shared.SharedStateHolder
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[26],application=TestNasApplication::class)
@OptIn(ExperimentalCoroutinesApi::class)
class AuthSessionRecheckTest {
    private val base="http://100.90.135.102:8822/webdav/"
    private val context get()=ApplicationProvider.getApplicationContext<TestNasApplication>()
    private lateinit var started: CompletableDeferred<Unit>
    private lateinit var route: CompletableDeferred<String>
    private lateinit var model: AuthSessionViewModel

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        WebDavManager.disconnect();SharedStateHolder.reset()
        started=CompletableDeferred();route=CompletableDeferred()
        mockkObject(SmartNetworkManager,SecurePrefsHelper)
        every {SecurePrefsHelper.getUser(any())} returns "fixture"
        every {SecurePrefsHelper.getPass(any())} returns "secret"
        mockkStatic("com.nas.naswebdav.NasApplicationKt")
        every {any<String>().toApiBaseUrl()} returns "http://127.0.0.1:9"
        coEvery {SmartNetworkManager.getVerifiedBaseUrl(any())} coAnswers {started.complete(Unit);route.await()}
        model=AuthSessionViewModel(mockk(relaxed=true))
    }

    @After fun cleanup() {
        unmockkObject(SmartNetworkManager,SecurePrefsHelper,WebDavManager,SharedStateHolder)
        unmockkStatic("com.nas.naswebdav.NasApplicationKt")
        WebDavManager.disconnect();SharedStateHolder.reset()
        Dispatchers.resetMain()
    }

    @Test(timeout=15000) fun verifiedRouteBadgeSurvivesRevalidationAndCancellation()=runBlocking {
        WebDavManager.connect(base,"fixture","secret")
        val active=ConnectionStatus.OnTailscale(base)
        SharedStateHolder.updateCurrentUrl(base);SharedStateHolder.updateConnectionStatus(active)
        val check=launch(Dispatchers.Default) {model.ensureSmartNetwork(context)}
        started.await()
        try {assertEquals(active,SharedStateHolder.connectionStatus.value)} finally {check.cancelAndJoin()}
        assertEquals(active,SharedStateHolder.connectionStatus.value)
    }

    @Test(timeout=15000) fun cancelledInitialCheckDoesNotLeaveCheckingBadge()=runBlocking {
        val check=launch(Dispatchers.Default) {model.ensureSmartNetwork(context)}
        started.await()
        check.cancelAndJoin()
        assertEquals(ConnectionStatus.Idle,SharedStateHolder.connectionStatus.value)
    }

    @Test(timeout=15000) fun logoutDuringCheckCannotReconnectSavedCredentials()=runBlocking {
        WebDavManager.connect(base,"fixture","secret")
        SharedStateHolder.updateCurrentUrl(base)
        SharedStateHolder.updateConnectionStatus(ConnectionStatus.OnTailscale(base))
        val check=async(Dispatchers.Default) {model.ensureSmartNetwork(context)}
        started.await()
        WebDavManager.disconnect();SharedStateHolder.reset()
        route.complete(base)
        assertEquals("",check.await())
        assertEquals("",WebDavManager.currentBaseUrl)
        assertEquals(ConnectionStatus.Idle,SharedStateHolder.connectionStatus.value)
    }

    @Test(timeout=15000) fun queuedCheckCapturedBeforeLogoutCannotStartANewSession()=runBlocking {
        WebDavManager.connect(base,"fixture","secret")
        SharedStateHolder.updateConnectionStatus(ConnectionStatus.OnTailscale(base))
        val first=async(Dispatchers.Default) {model.ensureSmartNetwork(context)}
        started.await()
        val queued=async(start=CoroutineStart.UNDISPATCHED) {model.ensureSmartNetwork(context)}
        WebDavManager.disconnect();SharedStateHolder.reset()
        route.complete(base)
        assertEquals("",first.await());assertEquals("",queued.await())
        coVerify(exactly=1) {SmartNetworkManager.getVerifiedBaseUrl(any())}
        assertEquals(ConnectionStatus.Idle,SharedStateHolder.connectionStatus.value)
    }

    @Test(timeout=15000) fun oldCheckCannotReplaceDifferentLoggedInAccount()=runBlocking {
        WebDavManager.connect(base,"fixture","secret")
        SharedStateHolder.updateConnectionStatus(ConnectionStatus.OnTailscale(base))
        val check=async(Dispatchers.Default) {model.ensureSmartNetwork(context)}
        started.await()
        val next="http://192.168.100.254:8822/webdav/"
        WebDavManager.connect(next,"next-account","next-secret")
        val newSession=WebDavManager.currentAuthState()
        val newStatus=ConnectionStatus.OnLan(next)
        SharedStateHolder.updateConnectionStatus(newStatus)
        route.complete(base)
        assertEquals(next,check.await())
        assertSame(newSession,WebDavManager.currentAuthState())
        assertEquals(newStatus,SharedStateHolder.connectionStatus.value)
    }

    @Test(timeout=15000) fun cancellationWhilePublishingInitialCheckRestoresIdle()=runBlocking {
        lateinit var check: Job
        mockkObject(SharedStateHolder)
        every {SharedStateHolder.updateConnectionStatus(ConnectionStatus.CheckingLan)} answers {
            callOriginal();check.cancel();Unit
        }
        check=launch(Dispatchers.Default,start=CoroutineStart.LAZY) {model.ensureSmartNetwork(context)}
        check.start();check.join()
        assertEquals(ConnectionStatus.Idle,SharedStateHolder.connectionStatus.value)
    }

    @Test(timeout=15000) fun cancellationImmediatelyAfterRouteCommitKeepsAllRouteFieldsCoherent()=runBlocking {
        lateinit var check: Job
        mockkObject(WebDavManager)
        every {WebDavManager.connect(base,"fixture","secret")} answers {
            callOriginal();check.cancel();Unit
        }
        route.complete(base)
        check=launch(Dispatchers.Default,start=CoroutineStart.LAZY) {model.ensureSmartNetwork(context)}
        check.start();check.join()
        assertEquals(base,WebDavManager.currentBaseUrl)
        assertEquals(base,SharedStateHolder.currentUrl.value)
        assertFalse(SharedStateHolder.isOnLan.value)
        assertEquals(ConnectionStatus.OnTailscale(base),SharedStateHolder.connectionStatus.value)
    }
}
