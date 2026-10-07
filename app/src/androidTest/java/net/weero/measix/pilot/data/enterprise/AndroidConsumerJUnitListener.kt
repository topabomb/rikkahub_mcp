package net.weero.measix.pilot.data.enterprise

import android.os.Bundle
import android.util.Base64
import android.util.Xml
import androidx.test.platform.app.InstrumentationRegistry
import java.io.StringWriter
import org.junit.runner.Description
import org.junit.runner.Result
import org.junit.runner.notification.Failure
import org.junit.runner.notification.RunListener

/** Emits the actual native JUnit events to adb; the host preserves the XML and raw output. */
class AndroidConsumerJUnitListener : RunListener() {
    private data class Case(val description: Description, var failure: Failure? = null, var skipped: Boolean = false)
    private val cases = linkedMapOf<Description, Case>()

    override fun testStarted(description: Description) { cases[description] = Case(description) }
    override fun testFailure(failure: Failure) {
        cases.getOrPut(failure.description) { Case(failure.description) }.failure = failure
    }
    override fun testAssumptionFailure(failure: Failure) {
        cases.getOrPut(failure.description) { Case(failure.description) }.skipped = true
    }
    override fun testIgnored(description: Description) { cases[description] = Case(description, skipped = true) }

    override fun testRunFinished(result: Result) {
        val writer = StringWriter()
        val xml = Xml.newSerializer().apply { setOutput(writer) }
        xml.startDocument("UTF-8", true)
        xml.startTag(null, "testsuite")
        xml.attribute(null, "name", "android-core-consumer")
        xml.attribute(null, "tests", cases.size.toString())
        xml.attribute(null, "failures", cases.values.count { it.failure != null }.toString())
        xml.attribute(null, "errors", "0")
        xml.attribute(null, "skipped", cases.values.count { it.skipped }.toString())
        for (case in cases.values) {
            xml.startTag(null, "testcase")
            xml.attribute(null, "classname", case.description.className ?: "instrumentation")
            xml.attribute(null, "name", case.description.methodName ?: case.description.displayName)
            if (case.skipped) { xml.startTag(null, "skipped"); xml.endTag(null, "skipped") }
            case.failure?.let {
                xml.startTag(null, "failure")
                xml.attribute(null, "type", it.exception.javaClass.name)
                xml.text(it.trace)
                xml.endTag(null, "failure")
            }
            xml.endTag(null, "testcase")
        }
        xml.endTag(null, "testsuite")
        xml.endDocument()
        val encoded = Base64.encodeToString(writer.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("stream", "\nMEASIX_JUNIT_BASE64=$encoded\n")
        })
    }
}
