package org.koitharu.kotatsu.scrobbling.common.data;

import android.content.SharedPreferences;
import java.util.*;

/** Android-free preference fixture for persistence, ownership and retry tests. */
public final class MemoryPreferences implements SharedPreferences {
    private final Map<String, Object> values = new HashMap<>();
    public synchronized Map<String, ?> getAll() { return new HashMap<>(values); }
    public synchronized String getString(String k, String d) { return (String) values.getOrDefault(k, d); }
    @SuppressWarnings("unchecked")
    public synchronized Set<String> getStringSet(String k, Set<String> d) { return (Set<String>) values.getOrDefault(k, d); }
    public synchronized int getInt(String k, int d) { return (Integer) values.getOrDefault(k, d); }
    public synchronized long getLong(String k, long d) { return (Long) values.getOrDefault(k, d); }
    public synchronized float getFloat(String k, float d) { return (Float) values.getOrDefault(k, d); }
    public synchronized boolean getBoolean(String k, boolean d) { return (Boolean) values.getOrDefault(k, d); }
    public synchronized boolean contains(String k) { return values.containsKey(k); }
    public Editor edit() { return new Edit(); }
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { }
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { }
    private class Edit implements Editor {
        private final Map<String, Object> pending = new HashMap<>();
        private final Set<String> removed = new HashSet<>();
        private boolean clear = false;
        public Editor putString(String k, String v) { if (v == null) return remove(k); pending.put(k, v); return this; }
        public Editor putStringSet(String k, Set<String> v) { if (v == null) return remove(k); pending.put(k, new HashSet<>(v)); return this; }
        public Editor putInt(String k, int v) { pending.put(k, v); return this; }
        public Editor putLong(String k, long v) { pending.put(k, v); return this; }
        public Editor putFloat(String k, float v) { pending.put(k, v); return this; }
        public Editor putBoolean(String k, boolean v) { pending.put(k, v); return this; }
        public Editor remove(String k) { removed.add(k); pending.remove(k); return this; }
        public Editor clear() { clear = true; return this; }
        public boolean commit() { synchronized (MemoryPreferences.this) {
            if (clear) values.clear(); removed.forEach(values::remove); values.putAll(pending);
        } return true; }
        public void apply() { commit(); }
    }
}
