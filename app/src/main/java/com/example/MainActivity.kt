package com.example

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.*; import android.content.*; import android.content.pm.*
import android.net.Uri; import android.os.*; import android.provider.Settings
import android.service.notification.*; import android.speech.*; import android.speech.tts.*
import android.util.Xml; import android.view.accessibility.*
import androidx.activity.*; import androidx.activity.compose.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*; import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*; import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*; import androidx.compose.foundation.text.*
import androidx.compose.material.icons.Icons; import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*; import androidx.compose.material3.*
import androidx.compose.runtime.*; import androidx.compose.ui.*; import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*; import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext; import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*; import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextOverflow; import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat; import androidx.core.content.ContextCompat
import androidx.lifecycle.*; import androidx.lifecycle.compose.LocalLifecycleOwner; import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.*; import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.*; import okhttp3.MediaType.Companion.toMediaType; import okhttp3.RequestBody.Companion.toRequestBody
import org.json.*; import org.xmlpull.v1.XmlPullParser
import java.io.StringReader; import java.lang.ref.WeakReference; import java.text.SimpleDateFormat
import java.util.*; import java.util.concurrent.TimeUnit

private const val PERSONA = "You are JARVIS, the user's hyper-intelligent, witty, and loyal best friend. Speak naturally and concisely (1–2 sentences max) for voice TTS, never use markdown symbols, and proactively connect weather, news, reminders, and app data like a real friend."
private const val ACTION_LISTEN_NOW = "com.example.jarvis.LISTEN_NOW"
private const val ACTION_PROCESS_TEXT = "com.example.jarvis.PROCESS_TEXT"
private const val ACTION_SPEAK = "com.example.jarvis.SPEAK"

object JarvisState {
  val status = MutableStateFlow("Standby")
  val serviceRunning = MutableStateFlow(false)
  val rmsLevel = MutableStateFlow(0f)
  val logs = MutableStateFlow<List<String>>(emptyList())
  val notifications = MutableStateFlow<List<String>>(emptyList())
  var accessibility: WeakReference<JarvisAccessibilityService>? = null
  private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
  fun log(msg: String) { logs.value = (listOf("${timeFmt.format(Date())}  $msg") + logs.value).take(40) }
  fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)
  fun apiKey(ctx: Context): String = prefs(ctx).getString("api_key", "") ?: ""
  fun saveApiKey(ctx: Context, key: String) = prefs(ctx).edit().putString("api_key", key.trim()).apply()
}

class JarvisVoiceService : Service() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val main = Handler(Looper.getMainLooper())
  private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
  private var wakeLock: PowerManager.WakeLock? = null
  private var recognizer: SpeechRecognizer? = null
  private var tts: TextToSpeech? = null
  private var ttsReady = false
  private var awaitingCommand = false
  private val history = mutableListOf<JSONObject>()
  override fun onBind(intent: Intent?) = null

  override fun onCreate() {
    super.onCreate()
    wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jarvis:VoiceWakeLock").apply { acquire(10 * 3600_000L) }
    if (Build.VERSION.SDK_INT >= 26) (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(NotificationChannel("jarvis_voice", "JARVIS Voice", NotificationManager.IMPORTANCE_LOW))
    val notif = NotificationCompat.Builder(this, "jarvis_voice").setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("JARVIS Always-On")
      .setContentText("Listening for \"Hey Jarvis\"").setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)).setOngoing(true).build()
    val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    if (Build.VERSION.SDK_INT >= 29) startForeground(1, notif, if (hasMic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(1, notif)
    tts = TextToSpeech(this) { s ->
      ttsReady = s == TextToSpeech.SUCCESS
      if (ttsReady) {
        tts?.language = Locale.US
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
          override fun onStart(id: String?) { JarvisState.status.value = "Speaking" }
          override fun onDone(id: String?) { main.post { if (JarvisState.serviceRunning.value) startListeningDelayed(250) else JarvisState.status.value = "Standby" } }
          @Deprecated("Deprecated") override fun onError(id: String?) { main.post { startListeningDelayed(400) } }
        })
      }
    }
    JarvisState.serviceRunning.value = true; JarvisState.log("Always-on voice loop started"); startListeningDelayed(300)
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_LISTEN_NOW -> main.post { tts?.stop(); awaitingCommand = true; JarvisState.log("Tap-to-talk active"); restartRecognizer() }
      ACTION_PROCESS_TEXT -> intent.getStringExtra("text")?.takeIf { it.isNotBlank() }?.let { txt -> tts?.stop(); JarvisState.log("You: $txt"); scope.launch { handleCommand(txt) } }
      ACTION_SPEAK -> intent.getStringExtra("text")?.let { speak(it) }
    }
    return START_STICKY
  }

  private fun startListeningDelayed(delayMs: Long) { main.removeCallbacksAndMessages(null); main.postDelayed({ if (JarvisState.serviceRunning.value) restartRecognizer() }, delayMs) }

  private fun restartRecognizer() {
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { JarvisState.status.value = "Mic needed"; return }
    runCatching {
      recognizer?.destroy()
      if (!SpeechRecognizer.isRecognitionAvailable(this)) { JarvisState.status.value = "No speech engine"; return }
      recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
        setRecognitionListener(object : RecognitionListener {
          override fun onReadyForSpeech(p: Bundle?) { JarvisState.status.value = if (awaitingCommand) "Listening (Active)" else "Listening" }
          override fun onBeginningOfSpeech() { if (tts?.isSpeaking == true) tts?.stop() }
          override fun onRmsChanged(rmsdB: Float) { JarvisState.rmsLevel.value = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
          override fun onBufferReceived(b: ByteArray?) {}
          override fun onEndOfSpeech() { JarvisState.rmsLevel.value = 0f }
          override fun onError(err: Int) { JarvisState.rmsLevel.value = 0f; startListeningDelayed(if (err == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1000 else 350) }
          override fun onPartialResults(p: Bundle?) { if (p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.contains("jarvis", true) == true && tts?.isSpeaking == true) tts?.stop() }
          override fun onResults(r: Bundle?) {
            JarvisState.rmsLevel.value = 0f
            val heard = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
            if (heard.isEmpty()) { startListeningDelayed(300); return }
            if (awaitingCommand || heard.contains("jarvis", true)) {
              val cmd = heard.replace(Regex("(?i)\\b(hey|ok|hi)?\\s*jarvis[,:]?\\s*"), "").trim()
              if (cmd.isEmpty()) { awaitingCommand = true; JarvisState.log("Wake word detected"); speak("Yes?") }
              else { awaitingCommand = false; JarvisState.log("Heard: $cmd"); scope.launch { handleCommand(cmd) } }
            } else startListeningDelayed(250)
          }
          override fun onEvent(t: Int, b: Bundle?) {}
        })
        startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
          putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM); putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true); putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        })
      }
    }.onFailure { JarvisState.log("Recognizer error: ${it.message}"); startListeningDelayed(1200) }
  }

  private suspend fun handleCommand(prompt: String) {
    val key = JarvisState.apiKey(this)
    if (key.isBlank()) { speak("Add your Gemini API key in the setup box first."); return }
    JarvisState.status.value = "Thinking"
    try {
      history.add(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt))))
      while (history.size > 12) history.removeAt(0)
      var reply = ""
      for (turn in 0..4) {
        val body = JSONObject().put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", PERSONA))))
          .put("contents", JSONArray(history)).put("tools", JSONArray().put(JSONObject().put("functionDeclarations", toolDeclarations())))
        val req = Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$key")
          .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        val resp = http.newCall(req).execute().use { r -> val raw = r.body?.string().orEmpty(); if (!r.isSuccessful) throw IllegalStateException("API ${r.code}: ${raw.take(120)}"); JSONObject(raw) }
        val content = resp.getJSONArray("candidates").getJSONObject(0).getJSONObject("content")
        history.add(content); val parts = content.getJSONArray("parts")
        val calls = (0 until parts.length()).mapNotNull { parts.getJSONObject(it).optJSONObject("functionCall") }
        if (calls.isEmpty()) { reply = (0 until parts.length()).mapNotNull { parts.getJSONObject(it).optString("text").takeIf(String::isNotBlank) }.joinToString(" "); break }
        val fnParts = JSONArray()
        for (fc in calls) {
          val name = fc.getString("name"); val args = fc.optJSONObject("args") ?: JSONObject()
          JarvisState.log("Tool: $name($args)")
          fnParts.put(JSONObject().put("functionResponse", JSONObject().put("name", name).put("response", JSONObject().put("result", executeTool(name, args)))))
        }
        history.add(JSONObject().put("role", "user").put("parts", fnParts))
      }
      val clean = reply.replace(Regex("[*_`#>]"), "").trim().ifBlank { "Done." }
      JarvisState.log("JARVIS: $clean"); speak(clean)
    } catch (e: Exception) { JarvisState.log("Error: ${e.message}"); speak("I hit a snag: ${e.message?.take(60) ?: "unknown error"}") }
  }

  private fun toolDeclarations(): JSONArray = JSONArray().apply {
    fun fn(n: String, d: String, p: JSONObject) = JSONObject().put("name", n).put("description", d).put("parameters", JSONObject().put("type", "OBJECT").put("properties", p))
    fun str(d: String) = JSONObject().put("type", "STRING").put("description", d)
    fun intP(d: String) = JSONObject().put("type", "INTEGER").put("description", d)
    put(fn("get_weather", "Get live weather from Open-Meteo", JSONObject().put("city", str("Optional city name"))))
    put(fn("get_news", "Get live Google News RSS headlines", JSONObject().put("topic", str("Optional topic"))))
    put(fn("set_reminder", "Save and schedule a voice reminder", JSONObject().put("message", str("Reminder text")).put("delay_minutes", intP("Minutes from now"))))
    put(fn("get_reminders", "List saved local reminders", JSONObject()))
    put(fn("get_notifications", "Read captured WhatsApp, Gmail, and app notifications", JSONObject().put("app_filter", str("Optional app filter"))))
    put(fn("control_phone", "Control apps, screen UI, calls, and messages", JSONObject()
      .put("action", str("launch_app, click_text, scroll_down, scroll_up, home, back, call, send_whatsapp, or send_sms"))
      .put("target", str("App name, UI text, phone number, or contact")).put("extra", str("Message body"))))
  }

  private fun executeTool(name: String, args: JSONObject): String = when (name) {
    "get_weather" -> fetchWeather(args.optString("city"))
    "get_news" -> fetchNews(args.optString("topic"))
    "set_reminder" -> scheduleReminder(args.optString("message", "Reminder"), args.optInt("delay_minutes", 5).coerceAtLeast(1))
    "get_reminders" -> JarvisState.prefs(this).getString("reminders_json", "[]") ?: "[]"
    "get_notifications" -> JarvisState.notifications.value.filter { args.optString("app_filter").isBlank() || it.contains(args.optString("app_filter"), true) }.take(10).ifEmpty { listOf("No recent notifications captured.") }.joinToString("\n")
    "control_phone" -> controlPhone(args.optString("action"), args.optString("target"), args.optString("extra"))
    else -> "Unknown tool"
  }

  private fun fetchWeather(city: String): String = runCatching {
    var lat = 37.7749; var lon = -122.4194; var label = "San Francisco"
    if (city.isNotBlank()) http.newCall(Request.Builder().url("https://geocoding-api.open-meteo.com/v1/search?name=${Uri.encode(city)}&count=1").build()).execute().use {
      JSONObject(it.body?.string() ?: "{}").optJSONArray("results")?.optJSONObject(0)?.let { r -> lat = r.getDouble("latitude"); lon = r.getDouble("longitude"); label = r.optString("name", city) }
    }
    val cur = http.newCall(Request.Builder().url("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m&timezone=auto").build()).execute().use { JSONObject(it.body?.string() ?: "{}") }.getJSONObject("current")
    "Weather in $label: ${cur.getDouble("temperature_2m")}C, feels like ${cur.getDouble("apparent_temperature")}C, wind ${cur.getDouble("wind_speed_10m")} km/h, WMO ${cur.getInt("weather_code")}."
  }.getOrElse { "Weather fetch failed: ${it.message}" }

  private fun fetchNews(topic: String): String = runCatching {
    val url = if (topic.isBlank()) "https://news.google.com/rss?hl=en-US&gl=US&ceid=US:en" else "https://news.google.com/rss/search?q=${Uri.encode(topic)}&hl=en-US&gl=US&ceid=US:en"
    val p = Xml.newPullParser().apply { setInput(StringReader(http.newCall(Request.Builder().url(url).build()).execute().use { it.body?.string().orEmpty() })) }
    val titles = mutableListOf<String>(); var inItem = false
    while (p.eventType != XmlPullParser.END_DOCUMENT && titles.size < 5) {
      if (p.eventType == XmlPullParser.START_TAG && p.name == "item") inItem = true
      else if (p.eventType == XmlPullParser.START_TAG && p.name == "title" && inItem) titles.add(p.nextText())
      else if (p.eventType == XmlPullParser.END_TAG && p.name == "item") inItem = false
      p.next()
    }
    if (titles.isEmpty()) "No headlines found." else titles.joinToString(" | ")
  }.getOrElse { "News fetch failed: ${it.message}" }

  private fun scheduleReminder(message: String, delayMin: Int): String {
    val triggerAt = System.currentTimeMillis() + delayMin * 60_000L; val id = (triggerAt % Int.MAX_VALUE).toInt()
    val arr = JSONArray(JarvisState.prefs(this).getString("reminders_json", "[]")).put(JSONObject().put("id", id).put("message", message).put("triggerAt", triggerAt))
    JarvisState.prefs(this).edit().putString("reminders_json", arr.toString()).apply()
    val am = getSystemService(ALARM_SERVICE) as AlarmManager
    val pi = PendingIntent.getBroadcast(this, id, Intent(this, JarvisAlarmReceiver::class.java).putExtra("message", message).putExtra("id", id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    if (Build.VERSION.SDK_INT >= 31 && am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi) else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
    return "Reminder scheduled in $delayMin minutes: $message"
  }

  private fun controlPhone(action: String, target: String, extra: String): String {
    val acc = JarvisState.accessibility?.get()
    return when (action.lowercase()) {
      "launch_app" -> {
        val r = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0).firstOrNull { it.loadLabel(packageManager).toString().contains(target, true) }
        val i = r?.activityInfo?.packageName?.let { packageManager.getLaunchIntentForPackage(it) }
        if (i != null) { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); "Launched ${r.loadLabel(packageManager)}" } else "App '$target' not found"
      }
      "click_text" -> if (acc == null) "Accessibility needed" else if (acc.clickByText(target)) "Clicked $target" else "Not found: $target"
      "scroll_down" -> if (acc?.scroll(true) == true) "Scrolled down" else "Unable to scroll"
      "scroll_up" -> if (acc?.scroll(false) == true) "Scrolled up" else "Unable to scroll"
      "home" -> if (acc?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) == true) "Went Home" else "Accessibility needed"
      "back" -> if (acc?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) == true) "Went Back" else "Accessibility needed"
      "call" -> { val act = if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) Intent.ACTION_CALL else Intent.ACTION_DIAL; startActivity(Intent(act, Uri.parse("tel:${Uri.encode(target)}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); "Calling $target" }
      "send_whatsapp" -> {
        val d = target.filter { it.isDigit() || it == '+' }
        val i = if (d.isNotBlank()) Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/${d.trimStart('+')}?text=${Uri.encode(extra)}")) else Intent(Intent.ACTION_SEND).setPackage("com.whatsapp").setType("text/plain").putExtra(Intent.EXTRA_TEXT, extra)
        runCatching { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); "Opened WhatsApp" }.getOrElse { "WhatsApp unavailable" }
      }
      "send_sms" -> { startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(target)}")).putExtra("sms_body", extra).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); "Opened SMS" }
      else -> "Unsupported action: $action"
    }
  }

  private fun speak(text: String) { if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis_${System.currentTimeMillis()}") else startListeningDelayed(400) }
  override fun onDestroy() { JarvisState.serviceRunning.value = false; JarvisState.status.value = "Standby"; main.removeCallbacksAndMessages(null); recognizer?.destroy(); tts?.shutdown(); if (wakeLock?.isHeld == true) wakeLock?.release(); scope.cancel(); super.onDestroy() }
}

class JarvisAccessibilityService : AccessibilityService() {
  override fun onServiceConnected() { JarvisState.accessibility = WeakReference(this); JarvisState.log("Accessibility connected") }
  override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
  override fun onInterrupt() {}
  override fun onUnbind(intent: Intent?): Boolean { JarvisState.accessibility = null; return super.onUnbind(intent) }
  fun clickByText(query: String): Boolean {
    val q = ArrayDeque<AccessibilityNodeInfo>().apply { rootInActiveWindow?.let(::add) }
    while (q.isNotEmpty()) {
      val n = q.removeFirst()
      if ("${n.text ?: ""} ${n.contentDescription ?: ""}".contains(query, true)) {
        var cur: AccessibilityNodeInfo? = n; while (cur != null) { if (cur.isClickable) return cur.performAction(AccessibilityNodeInfo.ACTION_CLICK); cur = cur.parent }
      }
      for (i in 0 until n.childCount) n.getChild(i)?.let(q::addLast)
    }
    return false
  }
  fun scroll(forward: Boolean): Boolean {
    val q = ArrayDeque<AccessibilityNodeInfo>().apply { rootInActiveWindow?.let(::add) }
    while (q.isNotEmpty()) {
      val n = q.removeFirst(); if (n.isScrollable) return n.performAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
      for (i in 0 until n.childCount) n.getChild(i)?.let(q::addLast)
    }
    return false
  }
}

class JarvisNotificationService : NotificationListenerService() {
  private val fmt = SimpleDateFormat("HH:mm", Locale.US)
  override fun onNotificationPosted(sbn: StatusBarNotification) {
    if (sbn.packageName == packageName || (sbn.notification.flags and Notification.FLAG_ONGOING_EVENT) != 0) return
    val ex = sbn.notification.extras; val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
    val body = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
    if (title.isBlank() && body.isBlank()) return
    val app = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
    JarvisState.notifications.value = (listOf("[${fmt.format(Date(sbn.postTime))}] $app — $title: $body") + JarvisState.notifications.value).take(30)
    JarvisState.log("Captured $app notification")
  }
}

class JarvisAlarmReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val msg = intent.getStringExtra("message") ?: "Reminder"; val id = intent.getIntExtra("id", 0)
    val arr = JSONArray(JarvisState.prefs(context).getString("reminders_json", "[]")); val kept = JSONArray()
    for (i in 0 until arr.length()) if (arr.getJSONObject(i).optInt("id") != id) kept.put(arr.getJSONObject(i))
    JarvisState.prefs(context).edit().putString("reminders_json", kept.toString()).apply()
    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("jarvis_alerts", "JARVIS Reminders", NotificationManager.IMPORTANCE_HIGH))
    nm.notify(id, NotificationCompat.Builder(context, "jarvis_alerts").setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle("JARVIS Reminder").setContentText(msg).setAutoCancel(true).build())
    JarvisState.log("Reminder fired: $msg")
    if (JarvisState.serviceRunning.value) ContextCompat.startForegroundService(context, Intent(context, JarvisVoiceService::class.java).setAction(ACTION_SPEAK).putExtra("text", "Reminder: $msg"))
  }
}

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); enableEdgeToEdge(); setContent { MyApplicationTheme { JarvisScreen() } } }
}

@Composable
fun JarvisScreen() {
  val ctx = LocalContext.current; val lifecycleOwner = LocalLifecycleOwner.current
  val status by JarvisState.status.collectAsStateWithLifecycle(); val running by JarvisState.serviceRunning.collectAsStateWithLifecycle()
  val rms by JarvisState.rmsLevel.collectAsStateWithLifecycle(); val logs by JarvisState.logs.collectAsStateWithLifecycle()
  var apiKey by remember { mutableStateOf(JarvisState.apiKey(ctx)) }; var showKey by remember { mutableStateOf(false) }; var cmdInput by remember { mutableStateOf("") }; var tick by remember { mutableIntStateOf(0) }
  val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
  val perms = remember { buildList { add(Manifest.permission.RECORD_AUDIO); if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS) }.toTypedArray() }
  DisposableEffect(lifecycleOwner) { val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }; lifecycleOwner.lifecycle.addObserver(obs); onDispose { lifecycleOwner.lifecycle.removeObserver(obs) } }
  val hasMic = remember(tick) { ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }
  val hasAcc = remember(tick) { Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)?.contains(ctx.packageName) == true }
  val hasNotif = remember(tick) { Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")?.contains(ctx.packageName) == true }
  val hasBattery = remember(tick) { (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName) }
  val pulse by rememberInfiniteTransition(label = "orb").animateFloat(0.92f, 1.08f, infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "scale")
  val orbBoost by animateFloatAsState(if (status.contains("Listening") || status == "Speaking") 1f + rms * 0.25f else 0.96f, label = "boost")
  fun sendCmd() { if (cmdInput.isNotBlank()) { ContextCompat.startForegroundService(ctx, Intent(ctx, JarvisVoiceService::class.java).setAction(ACTION_PROCESS_TEXT).putExtra("text", cmdInput)); cmdInput = "" } }

  Scaffold(containerColor = MaterialTheme.colorScheme.background, contentWindowInsets = WindowInsets.safeDrawing) { pad ->
    Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column { Text("JARVIS", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground); Text(status.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
        FilledTonalButton(onClick = { if (running) ctx.stopService(Intent(ctx, JarvisVoiceService::class.java)) else if (!hasMic) micPerm.launch(perms) else ContextCompat.startForegroundService(ctx, Intent(ctx, JarvisVoiceService::class.java)) }, modifier = Modifier.heightIn(min = 48.dp).testTag("toggle_service_button")) {
          Icon(if (running) Icons.Default.MicOff else Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(if (running) "Stop Loop" else "Start Always-On")
        }
      }
      Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
        val pColor = MaterialTheme.colorScheme.primary; val gColor = MaterialTheme.colorScheme.tertiary; val sColor = MaterialTheme.colorScheme.surfaceVariant
        Box(Modifier.size(136.dp).clip(CircleShape).semantics { contentDescription = "Voice orb" }.testTag("voice_orb").clickable {
          if (!hasMic) micPerm.launch(perms) else if (!running) ContextCompat.startForegroundService(ctx, Intent(ctx, JarvisVoiceService::class.java))
          else ctx.startService(Intent(ctx, JarvisVoiceService::class.java).setAction(ACTION_LISTEN_NOW))
        }, contentAlignment = Alignment.Center) {
          Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            drawCircle(color = sColor, radius = r * pulse * 0.95f, style = Stroke(width = 3.dp.toPx()))
            drawCircle(brush = Brush.radialGradient(listOf(gColor.copy(alpha = 0.85f), pColor)), radius = r * 0.68f * orbBoost * (if (running) pulse else 1f))
          }
          Icon(if (status == "Thinking") Icons.Default.AutoAwesome else Icons.Default.GraphicEq, contentDescription = "Activate JARVIS", tint = Color.White, modifier = Modifier.size(32.dp))
        }
      }
      Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(value = apiKey, onValueChange = { apiKey = it; JarvisState.saveApiKey(ctx, it) }, label = { Text("Gemini API Key") }, singleLine = true,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { IconButton(onClick = { showKey = !showKey }, modifier = Modifier.testTag("toggle_key_visibility")) { Icon(if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = "Toggle key visibility") } },
            modifier = Modifier.fillMaxWidth().testTag("api_key_input"))
          Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = cmdInput, onValueChange = { cmdInput = it }, placeholder = { Text("Ask JARVIS anything...") }, singleLine = true,
              keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { sendCmd() }), modifier = Modifier.weight(1f).testTag("command_input"))
            FilledIconButton(onClick = { sendCmd() }, modifier = Modifier.size(48.dp).testTag("send_command_button")) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send command") }
          }
          Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PermChip("Mic", hasMic, "perm_mic") { micPerm.launch(perms) }
            PermChip("Accessibility", hasAcc, "perm_accessibility") { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            PermChip("Notifications", hasNotif, "perm_notifications") { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            PermChip("Battery", hasBattery, "perm_battery") { ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))) }
          }
        }
      }
      Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline), modifier = Modifier.weight(1f).fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
          Text("LIVE ACTION LOG", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(6.dp))
          if (logs.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Tap the orb or say \"Hey Jarvis\" to begin.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
          else LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxSize().testTag("action_log_list")) {
            items(logs) { line -> Text(line, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 3, overflow = TextOverflow.Ellipsis) }
          }
        }
      }
    }
  }
}

@Composable
private fun PermChip(label: String, granted: Boolean, tag: String, onClick: () -> Unit) {
  FilterChip(selected = granted, onClick = onClick, label = { Text(label) }, leadingIcon = { Icon(if (granted) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, contentDescription = null, modifier = Modifier.size(16.dp)) }, modifier = Modifier.heightIn(min = 48.dp).testTag(tag))
}
