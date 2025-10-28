package your.pkg;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class RmsChooserBypass implements IXposedHookLoadPackage {

    private static final String TAG = "[RMS-LSPOSED]";
    private static final String[] TG_PKGS = new String[]{
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "org.telegram.messenger.beta"
    };

    private static volatile long targetDid = 0L;
    private static volatile int targetAcc = 0;
    private static volatile String targetPath = null;
    private static volatile String targetMime = null;
    private static final Object lock = new Object();

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpp) throws Throwable {
        boolean tg = false;
        for (String p : TG_PKGS) if (p.equals(lpp.packageName)) { tg = true; break; }
        if (!tg) return;

        XposedBridge.log(TAG + " loaded in " + lpp.packageName);
        final ClassLoader cl = lpp.classLoader;

        final Class<?> AppLoader = XposedHelpers.findClass("org.telegram.messenger.ApplicationLoader", cl);

        // Register receiver in app context
        XposedHelpers.findAndHookMethod(AppLoader, "onCreate", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                final Context ctx = (Context) XposedHelpers.getStaticObjectField(AppLoader, "applicationContext");
                if (ctx == null) return;

                IntentFilter f = new IntentFilter("rms.ACTION_ATTACH");
                ctx.registerReceiver(new BroadcastReceiver() {
                    @Override public void onReceive(Context context, Intent intent) {
                        if (!"rms.ACTION_ATTACH".equals(intent.getAction())) return;
                        long did = intent.getLongExtra("did", 0L);
                        int acc = intent.getIntExtra("account", 0);
                        String path = intent.getStringExtra("path");
                        String mime = intent.getStringExtra("mime");
                        synchronized (lock) {
                            targetDid = did;
                            targetAcc = acc;
                            targetPath = path;
                            targetMime = mime;
                        }
                        XposedBridge.log(TAG + " got ACTION_ATTACH did=" + did + " acc=" + acc + " path=" + path + " mime=" + mime);
                    }
                }, f);

                XposedBridge.log(TAG + " receiver registered");
            }
        });

        // Hook DialogsActivity.onResume → auto-select chat
        try {
            final Class<?> DialogsActivity = XposedHelpers.findClass("org.telegram.ui.DialogsActivity", cl);
            XposedHelpers.findAndHookMethod(DialogsActivity, "onResume", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    final Activity act = (Activity) param.thisObject;
                    final long did;
                    synchronized (lock) { did = targetDid; }
                    if (did == 0L) return;

                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        try {
                            autoSelectDialog(act, did);
                            // clear once used
                            synchronized (lock) { targetDid = 0L; }
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + " autoSelectDialog error: " + Log.getStackTraceString(t));
                        }
                    }, 150);
                }
            });
        } catch (Throwable e) {
            XposedBridge.log(TAG + " hook DialogsActivity failed: " + Log.getStackTraceString(e));
        }
    }

    private static void autoSelectDialog(Activity act, long targetDid) throws Throwable {
        View root = act.getWindow().getDecorView();
        Object listView = findRecyclerListView(root);
        if (listView == null) { XposedBridge.log(TAG + " list view not found"); return; }

        Object adapter = XposedHelpers.callMethod(listView, "getAdapter");
        if (adapter == null) { XposedBridge.log(TAG + " adapter null"); return; }

        ArrayList<?> dialogs = null;
        for (Field f : adapter.getClass().getDeclaredFields()) {
            if (List.class.isAssignableFrom(f.getType())) {
                f.setAccessible(true);
                Object val = f.get(adapter);
                if (val instanceof ArrayList) {
                    ArrayList<?> arr = (ArrayList<?>) val;
                    if (!arr.isEmpty()) {
                        Object first = arr.get(0);
                        if (first != null && first.getClass().getName().contains("TLRPC$Dialog")) {
                            dialogs = arr; break;
                        }
                    } else {
                        // boşsa yine de bunu deneyebiliriz
                        dialogs = arr; // keep reference
                    }
                }
            }
        }
        if (dialogs == null) { XposedBridge.log(TAG + " dialogs list not found"); return; }

        int targetPos = -1;
        for (int i = 0; i < dialogs.size(); i++) {
            Object d = dialogs.get(i);
            long id = extractDialogId(d);
            if (id == targetDid) { targetPos = i; break; }
        }
        if (targetPos < 0) {
            XposedBridge.log(TAG + " dialog not in list (maybe filtered/search?)");
            return;
        }

        try { XposedHelpers.callMethod(listView, "scrollToPosition", targetPos); } catch (Throwable ignore) {}

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                Object lm = XposedHelpers.callMethod(listView, "getLayoutManager");
                View child = null;
                try {
                    child = (View) XposedHelpers.callMethod(lm, "findViewByPosition", targetPos);
                } catch (Throwable ignore) {}
                if (child == null) {
                    int count = (int) XposedHelpers.callMethod(listView, "getChildCount");
                    for (int i = 0; i < count; i++) {
                        View c = (View) XposedHelpers.callMethod(listView, "getChildAt", i);
                        if (c != null) { child = c; break; }
                    }
                }
                XposedHelpers.callMethod(listView, "performItemClick", child, targetPos, 0L);
                XposedBridge.log(TAG + " item clicked pos=" + targetPos + " did=" + targetDid);
            } catch (Throwable t) {
                XposedBridge.log(TAG + " performItemClick error: " + Log.getStackTraceString(t));
            }
        }, 120);
    }

    private static long extractDialogId(Object dialogObj) {
        if (dialogObj == null) return 0L;
        try {
            Field idF = dialogObj.getClass().getDeclaredField("id");
            idF.setAccessible(true);
            return idF.getLong(dialogObj);
        } catch (Throwable ignored) {}
        // fallback: any long field
        for (Field f : dialogObj.getClass().getDeclaredFields()) {
            if (f.getType() == long.class) {
                try {
                    f.setAccessible(true);
                    long v = f.getLong(dialogObj);
                    return v;
                } catch (Throwable ignored2) {}
            }
        }
        return 0L;
    }

    private static Object findRecyclerListView(View root) {
        if (root == null) return null;
        String name = root.getClass().getName();
        if (name.contains("RecyclerListView") || name.contains("RecyclerView")) return root;
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                Object res = findRecyclerListView(vg.getChildAt(i));
                if (res != null) return res;
            }
        }
        return null;
    }
}