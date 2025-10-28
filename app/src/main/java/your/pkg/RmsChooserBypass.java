package your.pkg;

import java.io.File;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Safe-mode iskeleti: enable dosyası yoksa yalnızca log. */
public class RmsChooserBypass implements IXposedHookLoadPackage {

    private static final String TAG = "RMS-BYPASS";

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        final String pkg = lpparam.packageName;
        final String proc = lpparam.processName;

        // Sadece Telegram fork’ları
        if (!"org.telegram.messenger".equals(pkg)
                && !"org.telegram.messenger.web".equals(pkg)
                && !"org.telegram.messenger.beta".equals(pkg)) {
            return;
        }

        // Sadece ana süreç (UI)
        if (proc == null || !proc.equals(pkg)) {
            XposedBridge.log(TAG + ": skip non-main process " + proc);
            return;
        }

        XposedBridge.log(TAG + ": loaded " + pkg + " (main proc)");

        // Safe toggle: bu dosya yoksa hiçbir hook çalışmaz
        File toggle1 = new File("/sdcard/rms_bypass_enable");
        File toggle2 = new File("/sdcard/Android/data/" + pkg + "/files/rms_bypass_enable");
        if (!toggle1.exists() && !toggle2.exists()) {
            XposedBridge.log(TAG + ": SAFE MODE (disabled) — create /sdcard/rms_bypass_enable to enable hooks");
            return;
        }

        XposedBridge.log(TAG + ": SAFE MODE OFF — hooks would be set here (currently none for stability)");
        // TODO: Buraya gerçek hook’ları ekleyeceğiz (ExternalActionActivity/Share akışı vb.)
    }
}
