package android.os;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class Bundle {
    private final Map<String, Object> mMap = new LinkedHashMap<String, Object>();

    public void putString(String key, String value) {
        mMap.put(key, value);
    }

    public void putInt(String key, int value) {
        mMap.put(key, Integer.valueOf(value));
    }

    public void putLong(String key, long value) {
        mMap.put(key, Long.valueOf(value));
    }

    public void putBoolean(String key, boolean value) {
        mMap.put(key, Boolean.valueOf(value));
    }

    public void putByteArray(String key, byte[] value) {
        mMap.put(key, value);
    }

    public Object get(String key) {
        return mMap.get(key);
    }

    public Set<String> keySet() {
        return mMap.keySet();
    }

    public String getString(String key) {
        Object v = mMap.get(key);
        return v instanceof String ? (String) v : null;
    }

    public int getInt(String key, int defaultValue) {
        Object v = mMap.get(key);
        return v instanceof Number ? ((Number) v).intValue() : defaultValue;
    }

    public long getLong(String key, long defaultValue) {
        Object v = mMap.get(key);
        return v instanceof Number ? ((Number) v).longValue() : defaultValue;
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        Object v = mMap.get(key);
        return v instanceof Boolean ? ((Boolean) v).booleanValue() : defaultValue;
    }

    public byte[] getByteArray(String key) {
        Object v = mMap.get(key);
        return v instanceof byte[] ? (byte[]) v : null;
    }
}
