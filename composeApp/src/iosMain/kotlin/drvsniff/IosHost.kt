package drvsniff

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.CoreLocation.*
import platform.Foundation.*
import platform.UIKit.*
import platform.darwin.NSObject
import kotlin.coroutines.resume

@OptIn(ExperimentalForeignApi::class)
private class LocDelegate(val onFix: (Fix) -> Unit, val say: (String) -> Unit) : NSObject(), CLLocationManagerDelegateProtocol {
    override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
        val l = didUpdateLocations.lastOrNull() as? CLLocation ?: return
        if (l.horizontalAccuracy < 0) return
        val (lat, lon) = l.coordinate.useContents { latitude to longitude }
        onFix(Fix(lat, lon, l.altitude, l.horizontalAccuracy, nowMs()))
    }

    override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
        say("GPS ERROR: ${didFailWithError.localizedDescription}")
    }
}

@OptIn(ExperimentalForeignApi::class)
class IosScanner : Scanner {
    private val mgr = CLLocationManager()
    private var del: LocDelegate? = null

    override fun start(cfg: ScanCfg, onFix: (Fix) -> Unit, onScan: (List<Ap>) -> Unit, say: (String) -> Unit): String? {
        val st = CLLocationManager.authorizationStatus()
        if (st == kCLAuthorizationStatusDenied || st == kCLAuthorizationStatusRestricted)
            return "LOCATION DENIED. ENABLE IT IN SETTINGS."
        val d = LocDelegate(onFix, say)
        del = d
        mgr.delegate = d
        mgr.desiredAccuracy = kCLLocationAccuracyBest
        mgr.distanceFilter = kCLDistanceFilterNone
        mgr.requestWhenInUseAuthorization()
        mgr.allowsBackgroundLocationUpdates = true      // needs UIBackgroundModes=location in Info.plist
        mgr.pausesLocationUpdatesAutomatically = false
        mgr.startUpdatingLocation()
        say("GPS ONLY: iOS HAS NO WI-FI SCAN API")
        return null
    }

    override fun stop() {
        mgr.stopUpdatingLocation()
        mgr.delegate = null
        del = null
    }
}

private class PickDelegate(val done: (String?) -> Unit) : NSObject(), UIDocumentPickerDelegateProtocol {
    @OptIn(ExperimentalForeignApi::class)
    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL
        done(url?.let { NSString.stringWithContentsOfURL(it, NSUTF8StringEncoding, null) })
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) { done(null) }
}

@OptIn(ExperimentalForeignApi::class)
class IosHost : Host {
    override val name = "iOS"
    override val wifiScan = false
    override val scanner: Scanner = IosScanner()

    private var picker: Any? = null
    private val root = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String
    private val dir = "$root/store".also {
        NSFileManager.defaultManager.createDirectoryAtPath(it, true, null, null)
    }

    override fun load(key: String): String =
        NSString.stringWithContentsOfFile("$dir/$key", NSUTF8StringEncoding, null) ?: ""

    override fun store(key: String, value: String): Boolean {
        val path = "$dir/$key"
        if (value.isEmpty()) { NSFileManager.defaultManager.removeItemAtPath(path, null); return true }
        return NSString.create(string = value).writeToFile(path, true, NSUTF8StringEncoding, null)
    }

    override fun export(fileName: String, text: String): String {
        val ok = NSString.create(string = text).writeToFile("$root/$fileName", true, NSUTF8StringEncoding, null)
        return if (ok) "Files app > On My iPhone > Drvsniff > $fileName" else "WRITE FAILED"
    }

    override suspend fun pickText(): String? = suspendCancellableCoroutine { cont ->
        val p = UIDocumentPickerViewController(
            documentTypes = listOf("public.comma-separated-values-text", "public.plain-text"),
            inMode = UIDocumentPickerModeImport,
        )
        val d = PickDelegate { t -> picker = null; if (cont.isActive) cont.resume(t) }
        picker = d
        p.delegate = d
        val root = UIApplication.sharedApplication.keyWindow?.rootViewController
        if (root == null) cont.resume(null) else root.presentViewController(p, true, null)
    }
}
