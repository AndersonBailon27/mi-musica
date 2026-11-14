package pe.jhon.mimusica;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.io.PrintWriter;
import java.io.StringWriter;

/** Guarda el último error que cerró la app, para mostrarlo al volver a abrirla. */
public final class CrashLog {
    private static boolean installed = false;

    private CrashLog() {}

    public static synchronized void install(Context c) {
        if (installed) return;
        installed = true;
        final Context app = c.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                StringWriter sw = new StringWriter();
                error.printStackTrace(new PrintWriter(sw));
                String info = "Versión " + BuildConfigInfo.version(app) + " · Android " + Build.VERSION.RELEASE
                        + " · " + Build.MANUFACTURER + " " + Build.MODEL + "\n\n" + sw;
                prefs(app).edit().putString("ultimo_error", info).commit();
            } catch (Throwable ignored) {
            }
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    public static String takeLast(Context c) {
        SharedPreferences p = prefs(c);
        String e = p.getString("ultimo_error", null);
        if (e != null) p.edit().remove("ultimo_error").apply();
        return e;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("errores", Context.MODE_PRIVATE);
    }

    /** Versión de la app sin depender de BuildConfig. */
    static final class BuildConfigInfo {
        static String version(Context c) {
            try {
                return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            } catch (Exception e) {
                return "?";
            }
        }
    }
}
