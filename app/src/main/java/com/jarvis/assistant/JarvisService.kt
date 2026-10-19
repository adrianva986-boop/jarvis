package com.jarvis.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Date
import java.util.Locale
import java.util.zip.ZipInputStream
import org.vosk.android.RecognitionListener as VoskListener

// ---- customize Jarvis here ----
private const val PERSONA =
    "You are JARVIS, a calm, witty, loyal British AI assistant living on the user's phone, like Tony Stark's. " +
    "Keep replies to one or two short spoken sentences. Plain text only, no markdown or lists  Always address the user as sir. Start or end most replies with the word sir."
private const val OLLAMA = "http://127.0.0.1:11434/api/chat"
private const val MODEL_NAME = "vosk-model-small-en-us-0.15"
private const val MODEL_URL = "https://alphacephei.com/vosk/models/$MODEL_NAME.zip"
private val WAKE = Regex("jarvis|jervis|travis")   // words that count as the wake word

class JarvisService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var loading = false
    private var voskRec: Recognizer? = null
    private var speechService: SpeechService? = null
    private var woke = false
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val history = mutableListOf<JSONObject>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val ch = NotificationChannel("jarvis", "Jarvis", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        tts = TextToSpeech(this) { s ->
            if (s == TextToSpeech.SUCCESS) {
                tts?.language = Locale.UK
                tts?.setPitch(0.85f)
                ttsReady = true
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = notif("Starting…")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else startForeground(1, n)
        if (speechService == null && recognizer == null && !loading) {
            if (model == null) loadModel() else startWake()
        }
        return START_NOT_STICKY
    }

    private fun notif(text: String): Notification =
        Notification.Builder(this, "jarvis")
            .setContentTitle("Jarvis")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

    private fun setNotif(text: String) =
        getSystemService(NotificationManager::class.java).notify(1, notif(text))

    // ---- load (and on first run, download) the offline wake-word model ----
    private fun loadModel() {
        loading = true
        Thread {
            val dir = File(filesDir, "vosk")
            try {
                val done = File(dir, "done")
                if (!done.exists()) {
                    main.post { setNotif("Downloading voice model (~40 MB)…") }
                    dir.deleteRecursively(); dir.mkdirs()
                    ZipInputStream(URL(MODEL_URL).openStream().buffered()).use { zin ->
                        var e = zin.nextEntry
                        while (e != null) {
                            val f = File(dir, e.name)
                            if (!f.canonicalPath.startsWith(dir.canonicalPath)) throw SecurityException("bad zip")
                            if (e.isDirectory) f.mkdirs() else {
                                f.parentFile?.mkdirs()
                                FileOutputStream(f).use { zin.copyTo(it) }
                            }
                            e = zin.nextEntry
                        }
                    }
                    done.writeText("ok")
                }
                model = Model(File(dir, MODEL_NAME).absolutePath)
                main.post { loading = false; startWake() }
            } catch (e: Exception) {
                dir.deleteRecursively()
                main.post { loading = false; setNotif("Voice model error: ${e.message}"); stopSelf() }
            }
        }.start()
    }

    // ---- wake word (offline, Vosk) ----
    private fun startWake() {
        val m = model ?: return
        try {
            val rec = Recognizer(m, 16000.0f)
            voskRec = rec
            val ss = SpeechService(rec, 16000.0f)
            speechService = ss
            woke = false
            ss.startListening(object : VoskListener {
                override fun onPartialResult(h: String?) = heard(h)
                override fun onResult(h: String?) = heard(h)
                override fun onFinalResult(h: String?) {}
                override fun onError(e: Exception?) {}
                override fun onTimeout() {}
            })
            setNotif("Listening for “Jarvis”")
        } catch (e: Exception) {
            setNotif("Wake word error: ${e.message}")
            stopSelf()
        }
    }

    private fun heard(json: String?) {
        val t = json?.lowercase() ?: return
        if (!woke && WAKE.containsMatchIn(t)) {
            woke = true
            main.post { onWake() }
        }
    }

    private fun stopWake() {
        try { speechService?.stop(); speechService?.shutdown() } catch (_: Exception) {}
        speechService = null
        try { voskRec?.close() } catch (_: Exception) {}
        voskRec = null
    }

    private fun onWake() {
        stopWake()
        setNotif("Listening…")
        try { ToneGenerator(AudioManager.STREAM_MUSIC, 80).startTone(ToneGenerator.TONE_PROP_BEEP) } catch (_: Exception) {}
        main.postDelayed({ listen() }, 350)
    }

    // ---- speech to text for the actual request ----
    private fun listen() {
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                closeRecognizer()
                if (text.isNullOrBlank()) startWake() else think(text)
            }
            override fun onError(error: Int) { closeRecognizer(); startWake() }
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(p: Bundle?) {}
            override fun onEvent(t: Int, p: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        r.startListening(i)
    }

    private fun closeRecognizer() {
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
    }

    // ---- brain (Ollama running in Termux) ----
    private fun think(text: String) {
        setNotif("Thinking…")
        Thread {
            val reply = ask(text)
            main.post { say(reply) { startWake() } }
        }.start()
    }

    private fun ask(text: String): String {
        val model = getSharedPreferences("jarvis", MODE_PRIVATE).getString("model", "llama3.2:3b") ?: "llama3.2:3b"
        return try {
            val msgs = JSONArray().put(
                JSONObject().put("role", "system").put("content", "$PERSONA Current time: ${Date()}")
            )
            history.takeLast(8).forEach { msgs.put(it) }
            val u = JSONObject().put("role", "user").put("content", text)
            msgs.put(u)
            val body = JSONObject().put("model", model).put("messages", msgs).put("stream", false).toString()
            val c = URL(OLLAMA).openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true
            c.connectTimeout = 5000; c.readTimeout = 180000
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
            val resp = c.inputStream.bufferedReader().use { it.readText() }
            val reply = JSONObject(resp).getJSONObject("message").getString("content").trim()
            history.add(u)
            history.add(JSONObject().put("role", "assistant").put("content", reply))
            reply.replace(Regex("[*_#`]"), "")
        } catch (e: Exception) {
            "I cannot reach my brain. Please make sure Ollama is running in Termux."
        }
    }

    // ---- voice ----
    private fun say(text: String, then: () -> Unit) {
        if (!ttsReady) { then(); return }
        setNotif("Speaking…")
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { main.post { then() } }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { main.post { then() } }
        })
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "j")
    }

    override fun onDestroy() {
        stopWake()
        closeRecognizer()
        try { model?.close() } catch (_: Exception) {}
        tts?.shutdown()
        super.onDestroy()
    }
}
