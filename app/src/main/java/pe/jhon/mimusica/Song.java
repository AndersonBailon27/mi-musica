package pe.jhon.mimusica;

import android.content.ContentUris;
import android.net.Uri;
import android.provider.MediaStore;

public class Song {
    public final long id;
    public final String title;
    public final String artist;
    public final String album;
    public final long albumId;
    public final long duration;
    /** Carpeta del celular donde está el archivo (ruta completa). */
    public final String folder;

    public Song(long id, String title, String artist, String album, long albumId, long duration, String folder) {
        this.id = id;
        this.title = title;
        this.artist = artist;
        this.album = album;
        this.albumId = albumId;
        this.duration = duration;
        this.folder = folder;
    }

    public Uri uri() {
        return ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
    }

    public static String fmt(long ms) {
        if (ms <= 0) return "0:00";
        long s = ms / 1000;
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        if (h > 0) return h + ":" + (m < 10 ? "0" : "") + m + ":" + (sec < 10 ? "0" : "") + sec;
        return m + ":" + (sec < 10 ? "0" : "") + sec;
    }
}
