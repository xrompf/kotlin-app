package drvsniff

import kotlinx.coroutines.*
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.net.Socket
import java.util.concurrent.TimeUnit

private val OS = System.getProperty("os.name").lowercase()

private fun exec(vararg cmd: String): String? = try {
    val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText()
    if (!p.waitFor(20, TimeUnit.SECONDS)) { p.destroyForcibly(); null } else out
} catch (e: Exception) { null }

// ---- parsers ----

private fun splitEsc(l: String): List<String> {
    val out = ArrayList<String>()
    val sb = StringBuilder()
    var i = 0
    while (i < l.length) {
        val c = l[i]
        if (c == '\\' && i + 1 < l.length) { sb.append(l[i + 1]); i += 2; continue }
        if (c == ':') { out.add(sb.toString()); sb.clear() } else sb.append(c)
        i++
    }
    out.add(sb.toString())
    return out
}

/** nmcli -t -f BSSID,SSID,CHAN,SIGNAL,SECURITY. Signal is 0-100 percent, so dBm is approximated. */
fun parseNmcli(t: String): List<Ap> = t.lineSequence().mapNotNull { l ->
    val f = splitEsc(l)
    if (f.size < 5) return@mapNotNull null
    val pct = f[3].toIntOrNull() ?: return@mapNotNull null
    Ap(f[0].lowercase(), f[1], f[4], f[2].toIntOrNull() ?: 0, pct / 2 - 100)
}.toList()

/** English-locale Windows only. */
fun parseNetsh(t: String): List<Ap> {
    val out = ArrayList<Ap>()
    var ssid = ""; var auth = ""; var bssid = ""; var sig = -1; var ch = 0
    fun flush() {
        if (bssid.isNotEmpty() && sig >= 0) out.add(Ap(bssid.lowercase(), ssid, auth, ch, sig / 2 - 100))
        bssid = ""; sig = -1; ch = 0
    }
    for (raw in t.lineSequence()) {
        val l = raw.trim()
        val k = l.substringBefore(':').trim()
        val v = l.substringAfter(':', "").trim()
        when {
            k.startsWith("BSSID") -> { flush(); bssid = v }
            k.startsWith("SSID") -> { flush(); ssid = v; auth = "" }
            k.startsWith("Authentication") -> auth = v
            k.startsWith("Signal") -> sig = v.removeSuffix("%").trim().toIntOrNull() ?: -1
            k.startsWith("Channel") -> ch = v.toIntOrNull() ?: 0
        }
    }
    flush()
    return out
}

private val MAC = Regex("([0-9a-fA-F]{1,2}:){5}[0-9a-fA-F]{1,2}")

/** Old macOS only: Apple removed the airport tool in recent releases. */
fun parseAirport(t: String): List<Ap> = t.lineSequence().mapNotNull { l ->
    val m = MAC.find(l) ?: return@mapNotNull null
    val rest = l.substring(m.range.last + 1).trim().split(Regex("\\s+"))
    if (rest.size < 2) return@mapNotNull null
    Ap(m.value.lowercase(), l.substring(0, m.range.first).trim(), rest.drop(4).joinToString(" "),
        rest[1].substringBefore(',').toIntOrNull() ?: 0, rest[0].toIntOrNull() ?: -100)
}.toList()

private fun scanOnce(): List<Ap>? = when {
    "linux" in OS -> exec("nmcli", "-t", "-f", "BSSID,SSID,CHAN,SIGNAL,SECURITY", "dev", "wifi", "list", "--rescan", "yes")?.let(::parseNmcli)
    "windows" in OS -> exec("netsh", "wlan", "show", "networks", "mode=bssid")?.let(::parseNetsh)
    "mac" in OS -> exec("/System/Library/PrivateFrameworks/Apple80211.framework/Versions/Current/Resources/airport", "-s")?.let(::parseAirport)
    else -> null
}

private fun num(l: String, k: String): Double? =
    Regex("\"$k\"\\s*:\\s*(-?[0-9.eE+-]+)").find(l)?.groupValues?.get(1)?.toDoubleOrNull()

// ---- scanner ----

class DesktopScanner : Scanner {
    private var scope: CoroutineScope? = null

    override fun start(cfg: ScanCfg, onFix: (Fix) -> Unit, onScan: (List<Ap>) -> Unit, say: (String) -> Unit): String? {
        val m = cfg.manual.split(',').map { it.trim().toDoubleOrNull() }
        val fixed = if (m.size == 2 && m[0] != null && m[1] != null) m[0]!! to m[1]!! else null
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = sc
        if (fixed != null) {
            sc.launch {
                while (isActive) {
                    withContext(Dispatchers.Main) { onFix(Fix(fixed.first, fixed.second, 0.0, 5.0, nowMs())) }
                    delay(1000)
                }
            }
        } else {
            sc.launch { gpsd(cfg.gpsd, onFix, say) }
        }
        sc.launch { wifi(cfg.intervalMs.coerceAtLeast(4000L), onScan, say) }
        return null
    }

    override fun stop() { scope?.cancel(); scope = null }

    private suspend fun gpsd(addr: String, onFix: (Fix) -> Unit, say: (String) -> Unit) {
        val host = if (':' in addr) addr.substringBeforeLast(':') else addr
        val port = if (':' in addr) addr.substringAfterLast(':').toIntOrNull() ?: 2947 else 2947
        while (currentCoroutineContext().isActive) {
            try {
                Socket(host.ifEmpty { "localhost" }, port).use { s ->
                    s.soTimeout = 15000
                    s.getOutputStream().apply { write("?WATCH={\"enable\":true,\"json\":true};\n".toByteArray()); flush() }
                    withContext(Dispatchers.Main) { say("GPSD CONNECTED") }
                    val r = s.getInputStream().bufferedReader()
                    while (currentCoroutineContext().isActive) {
                        val line = r.readLine() ?: break
                        if ("\"TPV\"" !in line) continue
                        if ((num(line, "mode") ?: 0.0) < 2) continue
                        val lat = num(line, "lat") ?: continue
                        val lon = num(line, "lon") ?: continue
                        val alt = num(line, "altHAE") ?: num(line, "alt") ?: 0.0
                        val acc = maxOf(num(line, "epx") ?: 10.0, num(line, "epy") ?: 10.0)
                        withContext(Dispatchers.Main) { onFix(Fix(lat, lon, alt, acc, nowMs())) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { say("GPSD: ${e.message ?: "NOT REACHABLE"}. SET A FIXED POSITION IN CFG?") }
                delay(4000)
            }
        }
    }

    private suspend fun wifi(every: Long, onScan: (List<Ap>) -> Unit, say: (String) -> Unit) {
        var warned = false
        while (currentCoroutineContext().isActive) {
            val aps = scanOnce()
            if (aps == null) {
                if (!warned) {
                    warned = true
                    withContext(Dispatchers.Main) { say("WI-FI SCAN FAILED: NO nmcli / netsh / airport, OR NO ADAPTER") }
                }
            } else withContext(Dispatchers.Main) { onScan(aps) }
            delay(every)
        }
    }
}

class DesktopHost : Host {
    override val name = "Desktop (${System.getProperty("os.name")})"
    override val wifiScan = true
    override val gpsConfig = true
    override val scanner: Scanner = DesktopScanner()

    private val dir = File(System.getProperty("user.home"), ".drvsniff/store").apply { mkdirs() }

    override fun load(key: String): String =
        runCatching { File(dir, key).takeIf { it.exists() }?.readText() ?: "" }.getOrDefault("")

    override fun store(key: String, value: String): Boolean = runCatching {
        val f = File(dir, key)
        if (value.isEmpty()) f.delete() else f.writeText(value)
        true
    }.getOrDefault(false)

    override fun export(fileName: String, text: String): String {
        val d = File(System.getProperty("user.home"), "Drvsniff").apply { mkdirs() }
        return File(d, fileName).apply { writeText(text) }.absolutePath
    }

    override suspend fun pickText(): String? {
        val d = FileDialog(null as Frame?, "Import WiGLE CSV", FileDialog.LOAD)
        d.isVisible = true
        val f = d.file ?: return null
        return runCatching { File(d.directory, f).readText() }.getOrNull()
    }
}
