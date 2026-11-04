package android.content;

public class Intent {
    private String mAction;
    private final android.os.Bundle mExtras = new android.os.Bundle();

    public Intent() {
    }

    public Intent(String action) {
        this.mAction = action;
    }

    public String getAction() {
        return mAction;
    }

    public Intent setAction(String action) {
        this.mAction = action;
        return this;
    }

    public android.os.Bundle getExtras() {
        return mExtras;
    }

    public Intent putExtra(String name, String value) {
        mExtras.putString(name, value);
        return this;
    }

    public Intent putExtra(String name, int value) {
        mExtras.putInt(name, value);
        return this;
    }

    public Intent putExtra(String name, long value) {
        mExtras.putLong(name, value);
        return this;
    }

    public Intent putExtra(String name, boolean value) {
        mExtras.putBoolean(name, value);
        return this;
    }

    public Intent putExtra(String name, byte[] value) {
        mExtras.putByteArray(name, value);
        return this;
    }
}
