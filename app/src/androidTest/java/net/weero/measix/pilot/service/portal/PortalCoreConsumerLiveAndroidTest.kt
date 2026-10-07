package net.weero.measix.pilot.service.portal

import android.util.Base64
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import net.weero.measix.pilot.RouteActivity
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.service.ApplicationRecoveryGate
import net.weero.measix.pilot.service.EnterpriseApplicationService
import net.weero.measix.pilot.service.EnterpriseExitService
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import kotlin.coroutines.resume

/** Opt-in: consumes the deployed Portal bytes and the original native document/session owners. */
@RunWith(AndroidJUnit4::class)
class PortalCoreConsumerLiveAndroidTest {
    @Test
    fun packagedPortalBridgePermissionsCancellationRealmAndExit() = runBlocking<Unit> {
        val arguments = InstrumentationRegistry.getArguments()
        val assetMaterial = arguments.getString("corePortalAssets")
        assumeTrue("Explicit deployed Portal material required", assetMaterial != null)
        val assets = JSONObject(String(Base64.decode(requireNotNull(assetMaterial), Base64.NO_WRAP), Charsets.UTF_8))
        val expectedOrigin = requireNotNull(arguments.getString("coreConsumerOrigin"))
        val expectedDeployment = requireNotNull(arguments.getString("coreConsumerDeploymentId"))
        val koin = GlobalContext.get()
        koin.get<ApplicationRecoveryGate>().awaitReady()
        val sessions = koin.get<EnterpriseSessionController>()
        val service = koin.get<EnterpriseApplicationService>()
        val original = requireNotNull(sessions.readPresentation().selection)
        val access = original.access as RealmAccess.Enterprise
        assertEquals(expectedDeployment, access.scope.authority.deploymentId)
        assertEquals(expectedOrigin, sessions.platformConfiguration(access).session.platform!!.connection.origin)
        val activity = CompletableDeferred<RouteActivity>()
        val scenario = ActivityScenario.launch(RouteActivity::class.java)
        scenario.onActivity { activity.complete(it) }
        var host: PortalWebView? = null
        var overlay: FrameLayout? = null
        try {
            withTimeout(120_000) {
                suspend fun open() {
                    withContext(Dispatchers.Main) {
                        val current = requireNotNull(sessions.readPresentation().selection)
                        host = service.openPortal(activity.await(), current) {}
                        overlay = FrameLayout(activity.await()).also { frame ->
                            frame.addView(requireNotNull(host).view)
                            activity.await().addContentView(frame, FrameLayout.LayoutParams(-1, -1))
                        }
                    }
                    waitFor { javascript(requireNotNull(host), "Boolean(window.MeasixHost && window.MeasixPortalDocument && document.querySelector('#app')?.children.length)") == "true" }
                    assertEquals(JSONObject.quote(expectedOrigin), javascript(requireNotNull(host), "location.origin"))
                    javascript(requireNotNull(host), """
                        window.__measixConsumerReplies={};
                        MeasixHost.addEventListener('message',e=>{const r=JSON.parse(e.data);window.__measixConsumerReplies[r.requestId]=r;});
                        window.__measixConsumerAssets=null;
                        (async()=>{
                          try {
                            const expected=$assets;
                            for(const [path,hash] of Object.entries(expected)){
                              const response=await fetch('/portal/'+path,{cache:'no-store'});
                              if(!response.ok)throw new Error('asset_http_'+response.status);
                              const bytes=await response.arrayBuffer();
                              const actual='sha256:'+Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',bytes)),b=>b.toString(16).padStart(2,'0')).join('');
                              if(actual!==hash)throw new Error('asset_hash_'+path);
                            }
                            window.__measixConsumerAssets='PASS';
                          }catch(e){window.__measixConsumerAssets=String(e);}
                        })();true;
                    """.trimIndent())
                    waitFor { javascript(requireNotNull(host), "window.__measixConsumerAssets") != "null" }
                    assertEquals("Actual package Portal assets", "\"PASS\"", javascript(requireNotNull(host), "window.__measixConsumerAssets"))
                }
                suspend fun send(id: String, method: String, params: JSONObject = JSONObject()) {
                    val request = JSONObject().put("bridgeVersion", 3).put("documentId", requireNotNull(host).document.id)
                        .put("requestId", id).put("method", method).put("params", params)
                    javascript(requireNotNull(host), "MeasixHost.postMessage(${JSONObject.quote(request.toString())});true;")
                }
                suspend fun reply(id: String): JSONObject {
                    var encoded = "null"
                    waitFor {
                        encoded = javascript(requireNotNull(host), "JSON.stringify(window.__measixConsumerReplies[${JSONObject.quote(id)}]??null)")
                        encoded != "\"null\"" && encoded != "null"
                    }
                    return JSONObject(org.json.JSONTokener(encoded).nextValue() as String).also {
                        assertEquals(3, it.getInt("bridgeVersion"))
                        assertEquals(requireNotNull(host).document.id, it.getString("documentId"))
                        assertEquals(id, it.getString("requestId"))
                    }
                }
                open()
                send("consumer-status", "getStatus")
                val status = reply("consumer-status").getJSONObject("result")
                assertTrue(status.getBoolean("managedReady"))
                assertTrue(status.getLong("appliedManagedGeneration") > 0)
                assertTrue(status.getJSONArray("capabilities").toString().contains("recordAudio"))
                send("consumer-unknown", "futureMethod")
                assertEquals("unsupported_method", reply("consumer-unknown").getJSONObject("error").getString("code"))
                send("consumer-invalid", "recordAudio", JSONObject().put("maxDurationSeconds", 0))
                assertEquals("invalid_request", reply("consumer-invalid").getJSONObject("error").getString("code"))
                val native = requireNotNull(host?.document?.native)
                send("consumer-permission", "recordAudio", JSONObject().put("maxDurationSeconds", 1))
                waitFor { native.capture.value != null }
                withContext(Dispatchers.Main) { requireNotNull(native.capture.value).permissionResult(false) }
                assertEquals("permission_denied", reply("consumer-permission").getJSONObject("error").getString("code"))
                waitFor { native.capture.value == null }
                send("consumer-record", "recordAudio", JSONObject().put("maxDurationSeconds", 1))
                waitFor { native.capture.value != null }
                send("consumer-cancel", "cancel", JSONObject().put("targetRequestId", "consumer-record"))
                reply("consumer-cancel").getJSONObject("result")
                waitFor { native.capture.value == null }
                send("consumer-logout-cancel", "logout")
                waitFor { native.prompt.value != null }
                withContext(Dispatchers.Main) { native.decide(requireNotNull(native.prompt.value), false) }
                assertEquals("user_cancelled", reply("consumer-logout-cancel").getJSONObject("error").getString("code"))
                assertEquals(original, sessions.readPresentation().selection)
                val closed = requireNotNull(host).document
                service.switchRealm(RealmSwitchRequest(original, RealmAccess.Personal))
                closed.awaitClosed()
                assertTrue(closed.isClosed)
                host = null
                withContext(Dispatchers.Main) { (overlay?.parent as? android.view.ViewGroup)?.removeView(overlay); overlay = null }
                service.switchRealm(RealmSwitchRequest(requireNotNull(sessions.readPresentation().selection), access))
                open()
                assertNotEquals(closed.id, requireNotNull(host).document.id)
                send("consumer-logout", "logout")
                val finalNative = requireNotNull(host?.document?.native)
                waitFor { finalNative.prompt.value != null }
                withContext(Dispatchers.Main) { finalNative.decide(requireNotNull(finalNative.prompt.value), true) }
                requireNotNull(host).document.awaitClosed()
                waitFor {
                    assertNull("Native exit failure: ${koin.get<EnterpriseExitService>().failure.value}", koin.get<EnterpriseExitService>().failure.value)
                    (sessions.readPresentation().state as? EnterpriseState.Available)?.let { it.manifest.session == null } == true
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                host?.let { it.close(); it.document.awaitClosed() }
                (overlay?.parent as? android.view.ViewGroup)?.removeView(overlay)
            }
            scenario.close()
        }
    }

    private suspend fun waitFor(condition: suspend () -> Boolean) {
        withTimeout(30_000) { while (!condition()) delay(50) }
    }

    private suspend fun javascript(host: PortalWebView, script: String): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            host.view.evaluateJavascript(script) { result -> if (continuation.isActive) continuation.resume(result) }
        }
    }
}
