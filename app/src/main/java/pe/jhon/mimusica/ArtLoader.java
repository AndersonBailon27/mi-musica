package pe.jhon.mimusica;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.util.Size;
import android.widget.ImageView;

import java.io.InputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Carga carátulas de las canciones en segundo plano, con caché. */
public final class ArtLoader {
    private static final LruCache<Long, Bitmap> cache = new LruCache<>(120);
    private static final Set<Long> missing = Collections.synchronizedSet(new HashSet<>());
    private static final ExecutorService pool = Executors.newFixedThreadPool(3);
    private static final Handler main = new Handler(Looper.getMainLooper());

    private ArtLoader() {}

    public static Bitmap loadSync(Context c, Song s) {
        Bitmap cached = cache.get(s.id);
        if (cached != null) return cached;
        if (missing.contains(s.id)) return null;
        Bitmap b = null;
        ContentResolver cr = c.getContentResolver();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                b = cr.loadThumbnail(s.uri(), new Size(320, 320), null);
            } else {
                Uri art = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), s.albumId);
                try (InputStream in = cr.openInputStream(art)) {
                    if (in != null) {
                        BitmapFactory.Options o = new BitmapFactory.Options();
                        o.inSampleSize = 2;
                        b = BitmapFactory.decodeStream(in, null, o);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        if (b == null) {
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            try {
                mmr.setDataSource(c, s.uri());
                byte[] pic = mmr.getEmbeddedPicture();
                if (pic != null) {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(pic, 0, pic.length, o);
                    int sample = 1;
                    while (o.outWidth / (sample * 2) >= 320) sample *= 2;
                    o = new BitmapFactory.Options();
                    o.inSampleSize = sample;
                    b = BitmapFactory.decodeByteArray(pic, 0, pic.length, o);
                }
            } catch (Throwable ignored) {
            } finally {
                try { mmr.release(); } catch (Throwable ignored) {}
            }
        }
        if (b != null) b = shrink(b, 320);
        if (b == null) missing.add(s.id); else cache.put(s.id, b);
        return b;
    }

    /** Reduce la imagen para que nunca sea más grande de lo necesario (evita cierres de la app). */
    public static Bitmap shrink(Bitmap b, int max) {
        if (b == null) return null;
        int w = b.getWidth(), h = b.getHeight();
        if (w <= max && h <= max) return b;
        float k = Math.min((float) max / w, (float) max / h);
        try {
            return Bitmap.createScaledBitmap(b, Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k)), true);
        } catch (Throwable e) {
            return null;
        }
    }

    public static void into(Context c, Song s, ImageView iv) {
        iv.setTag(s.id);
        Bitmap b = cache.get(s.id);
        if (b != null) {
            showBitmap(iv, b);
            return;
        }
        showPlaceholder(iv);
        if (missing.contains(s.id)) return;
        final Context app = c.getApplicationContext();
        pool.execute(() -> {
            final Bitmap r = loadSync(app, s);
            if (r == null) return;
            main.post(() -> {
                Object tag = iv.getTag();
                if (tag instanceof Long && (Long) tag == s.id) showBitmap(iv, r);
            });
        });
    }

    private static void showBitmap(ImageView iv, Bitmap b) {
        iv.setImageTintList(null);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setPadding(0, 0, 0, 0);
        iv.setImageBitmap(b);
    }

    private static void showPlaceholder(ImageView iv) {
        int p = iv.getLayoutParams() != null && iv.getLayoutParams().width > 0 ? iv.getLayoutParams().width / 4 : 12;
        iv.setPadding(p, p, p, p);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setImageResource(R.drawable.ic_note);
        iv.setImageTintList(ColorStateList.valueOf(0xFF8B90A0));
    }
}
