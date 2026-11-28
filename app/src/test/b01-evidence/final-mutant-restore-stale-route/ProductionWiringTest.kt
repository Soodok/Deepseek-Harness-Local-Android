package app.dsh.mobile.coldentry

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Adapter source contracts supplement executable helpers; they do not execute Android framework. */
class ProductionWiringTest {
    private fun text(name: String): String = File("src/main/java/app/dsh/mobile/" + name + ".kt").readText()
    private fun code(name: String): String {
        val tokens = Regex("(?s)/\\*.*?\\*/|//[^\\r\\n]*|\"\"\".*?\"\"\"|\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'")
        return tokens.replace(text(name)) { match ->
            match.value.map { if (it == '\n' || it == '\r') it else ' ' }.joinToString("")
        }.replace(Regex("""\s+"""), "")
    }
    @Test fun platformLaunchAndResultShareUniqueCodeNotAnEchoedCustomId() {
        val src=code("MainActivity")
        val launch=src.substringAfter("overridefunstartForResult(entry:NativeEntry){").substringBefore("overridefuntrace(")
        assertTrue(launch.contains("valcode=nativeEntryResults.launched(entry)"))
        assertTrue(launch.contains("startActivityForResult(Intent(this@MainActivity,NativeChatActivity::class.java)"))
        assertTrue(launch.contains("},code)")); assertFalse(launch.contains("REQ_NATIVE_CHAT"))
        assertTrue(launch.contains("nativeEntryResults.abandon(code)"))
        val result=src.substringAfter("overridefunonActivityResult(").substringBefore("if(requestCode==REQ_NATIVE_CHAT)")
        assertTrue(result.contains("valdelivered=nativeEntryResults.complete(requestCode)"))
        assertTrue(result.contains("if(delivered&&resultCode==RESULT_OK){sessionHandoff.enqueue(data?.getStringExtra(NativeChatActivity.EXTRA_SESSION_ID))}"))
        assertFalse(result.contains("getLongExtra")); assertFalse(result.contains("EXTRA_BACKDROP"))
    }
    @Test fun savedStateCarriesRoutesNotDeleteCapabilitiesAndRecreationKeepsProcessRegistry() {
        val src=code("MainActivity")
        assertTrue(src.contains("nativeEntryResultsget()=NativeEntryProcess.results"))
        assertTrue(src.contains("nativeEntryResults.restore("))
        val save=src.substringAfter("overridefunonSaveInstanceState(").substringBefore("overridefunonPause()")
        assertTrue(save.contains("outState.putInt(ENTRY_NEXT_CODE,nativeEntryResults.nextCode)"))
        assertTrue(save.contains("outState.putInt(ENTRY_CHILD_CODE,it)")); assertFalse(save.contains("EXTRA_BACKDROP"))
        for(lifecycle in listOf("onPause", "onDestroy")) {
            val callback=src.substringAfter("overridefun"+lifecycle+"(){")
            assertTrue(callback.startsWith("coldEntryCoordinator."+lifecycle+"()"))
        }
    }
    @Test fun pixelCopyAndWriterCloseUseExactLeaseAndCompressionBoolean() {
        val src=code("MainActivity"); val capture=src.substringAfter("privatefuncaptureWindowBackdrop(").substringBefore("privatefunreadCurrentWebSession(")
        assertTrue(capture.contains("coldEntryCoordinator.permitsCapture(target)"))
        assertTrue(capture.contains("ok=FileOutputStream(File(target.path)).use{out->bitmap.compress(Bitmap.CompressFormat.PNG,100,out)}"))
        assertTrue(capture.contains("target.writerFinished(ok)"))
        assertTrue(capture.indexOf("target.writerFinished(ok)") < capture.indexOf("entryHandler.post{"))
        assertFalse(capture.contains(".isSuccess")); assertFalse(capture.contains("view.draw("))
    }
    @Test fun existingSuccessfulSidAndFlutterCoverHandshakeRemain() {
        val src=code("NativeChatActivity")
        assertTrue(src.contains("setResult(RESULT_OK,Intent().putExtra(EXTRA_SESSION_ID,selected))"))
        assertTrue(src.contains("setResult(RESULT_CANCELED)"))
        assertTrue(src.contains("result.success(releaseBackdrop())"))
        assertTrue(src.contains("handler.postDelayed(readyTimeout,BACKDROP_READY_TIMEOUT_MS)"))
    }
}
