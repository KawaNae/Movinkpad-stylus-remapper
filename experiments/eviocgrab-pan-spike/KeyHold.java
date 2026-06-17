import android.os.IBinder;
import android.os.SystemClock;
import android.view.InputEvent;
import android.view.KeyEvent;
import java.lang.reflect.Method;

/** Hold a key down for N ms via InputManager.injectInputEvent (proven path). */
public class KeyHold {
    public static void main(String[] a) throws Exception {
        int keyCode = Integer.parseInt(a[0]);
        int holdMs  = Integer.parseInt(a[1]);

        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "input");
        Object im = Class.forName("android.hardware.input.IInputManager$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, binder);
        Method inject = im.getClass().getMethod("injectInputEvent", InputEvent.class, int.class);

        long t = SystemClock.uptimeMillis();
        KeyEvent down = new KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0, 0, -1, 0,
                KeyEvent.FLAG_FROM_SYSTEM, 0x101);
        inject.invoke(im, down, 0);
        System.err.println("[keyhold] down " + keyCode);
        Thread.sleep(holdMs);
        long t2 = SystemClock.uptimeMillis();
        KeyEvent up = new KeyEvent(t2, t2, KeyEvent.ACTION_UP, keyCode, 0, 0, -1, 0,
                KeyEvent.FLAG_FROM_SYSTEM, 0x101);
        inject.invoke(im, up, 0);
        System.err.println("[keyhold] up " + keyCode);
    }
}
