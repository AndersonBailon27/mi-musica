package pe.jhon.mimusica;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Guarda las carpetas propias del usuario y las canciones ocultas. */
public class Library {

    public static class Folder {
        public String name;
        public final List<Long> ids = new ArrayList<>();
        Folder(String name) { this.name = name; }
    }

    private final SharedPreferences prefs;
    public final List<Folder> folders = new ArrayList<>();
    public final Set<Long> hidden = new HashSet<>();

    public Library(Context c) {
        prefs = c.getSharedPreferences("biblioteca", Context.MODE_PRIVATE);
        load();
    }

    private void load() {
        folders.clear();
        hidden.clear();
        try {
            JSONArray arr = new JSONArray(prefs.getString("carpetas", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Folder f = new Folder(o.optString("nombre", "Carpeta"));
                JSONArray ids = o.optJSONArray("ids");
                if (ids != null) for (int j = 0; j < ids.length(); j++) f.ids.add(ids.getLong(j));
                folders.add(f);
            }
            JSONArray h = new JSONArray(prefs.getString("ocultas", "[]"));
            for (int i = 0; i < h.length(); i++) hidden.add(h.getLong(i));
        } catch (Exception ignored) {
        }
    }

    public void save() {
        try {
            JSONArray arr = new JSONArray();
            for (Folder f : folders) {
                JSONObject o = new JSONObject();
                o.put("nombre", f.name);
                JSONArray ids = new JSONArray();
                for (Long id : f.ids) ids.put(id);
                o.put("ids", ids);
                arr.put(o);
            }
            JSONArray h = new JSONArray();
            for (Long id : hidden) h.put(id);
            prefs.edit().putString("carpetas", arr.toString()).putString("ocultas", h.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    public Folder create(String name) {
        Folder f = new Folder(name);
        folders.add(f);
        save();
        return f;
    }

    public void delete(Folder f) {
        folders.remove(f);
        save();
    }

    public void rename(Folder f, String name) {
        f.name = name;
        save();
    }

    /** @return true si se agregó, false si ya estaba. */
    public boolean add(Folder f, long id) {
        if (f.ids.contains(id)) return false;
        f.ids.add(id);
        save();
        return true;
    }

    public void remove(Folder f, long id) {
        f.ids.remove(Long.valueOf(id));
        save();
    }

    public void hide(long id) {
        hidden.add(id);
        save();
    }

    public void unhide(long id) {
        hidden.remove(id);
        save();
    }
}
