package android.content;

public interface IIntentReceiver extends android.os.IInterface {
    void performReceive(
            android.content.Intent intent,
            int resultCode,
            String data,
            android.os.Bundle extras,
            boolean ordered,
            boolean sticky,
            int sendingUser
    );

    public static abstract class Stub extends android.os.Binder implements IIntentReceiver {
        public Stub() {
        }

        public static IIntentReceiver asInterface(android.os.IBinder obj) {
            return null;
        }

        @Override
        public android.os.IBinder asBinder() {
            return this;
        }
    }
}
