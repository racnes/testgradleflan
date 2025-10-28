package your.pkg;

import android.view.View;
import android.widget.ListView;

import androidx.recyclerview.widget.RecyclerView;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import de.robv.android.xposed.XposedHelpers;

/**
 * Minimal, derlenebilir iskelet.
 * - Lambda içinde yakalanan yerel değişkenler final/effectively-final kuralına uygun hale getirildi.
 * - child/targetPos/targetDid yeniden atama yok; lambda içinde final kopyalar kullanılıyor.
 * Bu sınıf LSPosed giriş noktası iskeleti sağlar; asıl hooklar başka yerde olabilir.
 */
public class RmsChooserBypass implements IXposedHookLoadPackage {

    private static final String TAG = "RMS-BYPASS";

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        // İsteğe bağlı: paket filtreleri (Telegram çeşitleri)
        if (!"org.telegram.messenger".equals(lpparam.packageName)
                && !"org.telegram.messenger.web".equals(lpparam.packageName)
                && !"org.telegram.messenger.beta".equals(lpparam.packageName)) {
            return;
        }
        XposedBridge.log(TAG + ": loaded " + lpparam.packageName);
        // Asıl hooklarınızı burada/başka sınıflarda kurabilirsiniz.
    }

    /**
     * Paylaş/chooser listesindeki target satırı tıklamak için yardımcı.
     * Lambda kuralları nedeniyle "final" kopyalar kullanılıyor.
     */
    public static void clickListItem(final Object listObj, final Object layoutManager, final int targetPos, final long targetDid) {
        if (listObj instanceof ListView) {
            final ListView listView = (ListView) listObj;
            final int p = targetPos;
            final long did = targetDid;
            listView.post(() -> {
                final int first = listView.getFirstVisiblePosition();
                final View childLocal = listView.getChildAt(p - first);
                if (childLocal != null) {
                    // performItemClick(Object view, int position, long id)
                    XposedHelpers.callMethod(listView, "performItemClick", childLocal, p, 0L);
                    XposedBridge.log(TAG + " item clicked (ListView) pos=" + p + " did=" + did);
                } else {
                    XposedBridge.log(TAG + " childLocal null (ListView) pos=" + p);
                }
            });
            return;
        }

        if (listObj instanceof RecyclerView) {
            final RecyclerView rv = (RecyclerView) listObj;
            final Object lm = (layoutManager != null) ? layoutManager : rv.getLayoutManager();
            final int p = targetPos;
            final long did = targetDid;
            rv.post(() -> {
                final View childLocal = (View) XposedHelpers.callMethod(lm, "findViewByPosition", p);
                if (childLocal != null) {
                    // RecyclerView için doğrudan performClick yeterli
                    childLocal.performClick();
                    XposedBridge.log(TAG + " item clicked (RecyclerView) pos=" + p + " did=" + did);
                } else {
                    XposedBridge.log(TAG + " childLocal null (RecyclerView) pos=" + p);
                }
            });
        }
    }
}
