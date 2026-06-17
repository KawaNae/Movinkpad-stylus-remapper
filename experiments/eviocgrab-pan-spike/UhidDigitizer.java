import android.system.Os;
import android.system.OsConstants;
import java.io.FileDescriptor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Verification spike #2: create a virtual ABSOLUTE-coordinate HID digitizer
 * (single-touch touchscreen) via /dev/uhid. Unlike the relative mouse spike,
 * this presents as a TOUCH-class device, which Android binds to the default
 * display without needing a mouse PointerController/cursor.
 *
 * Report (5 bytes): [tipSwitch(1 bit + 7 pad), X u16 LE, Y u16 LE]
 * Logical range 0..32767 maps to the display surface.
 *
 * Usage:
 *   UhidDigitizer center [holdMs]          - tap at logical center
 *   UhidDigitizer tap <X> <Y> [holdMs]     - tap at absolute 0..32767
 *   UhidDigitizer tappx <PX> <PY> <W> <H> [holdMs] - tap at pixel PX,PY of WxH
 *   UhidDigitizer press <X> <Y> [holdMs]   - tip-down and HOLD (combine w/ space)
 *   UhidDigitizer drag                     - tip-down, slide, release
 */
public class UhidDigitizer {

    static final int UHID_CREATE2 = 11;
    static final int UHID_DESTROY = 1;
    static final int UHID_INPUT2  = 12;

    static final int LOGICAL_MAX = 32767;

    // Single-touch absolute digitizer (touch screen)
    static final byte[] RD = new byte[] {
        0x05, 0x0D,             // USAGE_PAGE (Digitizers)
        0x09, 0x04,             // USAGE (Touch Screen)
        (byte)0xA1, 0x01,       // COLLECTION (Application)
        0x09, 0x22,             //   USAGE (Finger)
        (byte)0xA1, 0x02,       //   COLLECTION (Logical)
        0x09, 0x42,             //     USAGE (Tip Switch)
        0x15, 0x00,             //     LOGICAL_MINIMUM (0)
        0x25, 0x01,             //     LOGICAL_MAXIMUM (1)
        0x75, 0x01,             //     REPORT_SIZE (1)
        (byte)0x95, 0x01,       //     REPORT_COUNT (1)
        (byte)0x81, 0x02,       //     INPUT (Data,Var,Abs)
        (byte)0x95, 0x07,       //     REPORT_COUNT (7)
        (byte)0x81, 0x03,       //     INPUT (Cnst,Var,Abs)  -- padding
        0x05, 0x01,             //     USAGE_PAGE (Generic Desktop)
        0x09, 0x30,             //     USAGE (X)
        0x09, 0x31,             //     USAGE (Y)
        0x16, 0x00, 0x00,       //     LOGICAL_MINIMUM (0)
        0x26, (byte)0xFF, 0x7F, //     LOGICAL_MAXIMUM (32767)
        0x75, 0x10,             //     REPORT_SIZE (16)
        (byte)0x95, 0x02,       //     REPORT_COUNT (2)
        (byte)0x81, 0x02,       //     INPUT (Data,Var,Abs)
        (byte)0xC0,             //   END_COLLECTION
        (byte)0xC0              // END_COLLECTION
    };

    static FileDescriptor fd;

    public static void main(String[] args) throws Exception {
        String gesture = args.length > 0 ? args[0] : "center";

        fd = Os.open("/dev/uhid", OsConstants.O_RDWR, 0);
        log("opened /dev/uhid");

        Thread reader = new Thread(() -> {
            byte[] b = new byte[4380];
            try {
                while (true) {
                    int n = Os.read(fd, b, 0, b.length);
                    if (n <= 0) break;
                    log("kernel event type=" + (b[0] & 0xFF));
                }
            } catch (Exception e) { }
        });
        reader.setDaemon(true);
        reader.start();

        writeCreate();
        log("CREATE2 written, waiting for OPEN...");
        Thread.sleep(700);
        log("gesture=" + gesture);

        if (gesture.equals("center")) {
            int hold = args.length > 1 ? Integer.parseInt(args[1]) : 0;
            tap(LOGICAL_MAX / 2, LOGICAL_MAX / 2, hold);
            done(2000); return;
        }
        if (gesture.equals("tap")) {
            int x = Integer.parseInt(args[1]);
            int y = Integer.parseInt(args[2]);
            int hold = args.length > 3 ? Integer.parseInt(args[3]) : 0;
            tap(x, y, hold);
            done(2000); return;
        }
        if (gesture.equals("tappx")) {
            int px = Integer.parseInt(args[1]);
            int py = Integer.parseInt(args[2]);
            int w  = Integer.parseInt(args[3]);
            int h  = Integer.parseInt(args[4]);
            int hold = args.length > 5 ? Integer.parseInt(args[5]) : 0;
            int x = (int) ((long) px * LOGICAL_MAX / w);
            int y = (int) ((long) py * LOGICAL_MAX / h);
            log("pixel " + px + "," + py + " -> abs " + x + "," + y);
            tap(x, y, hold);
            done(2000); return;
        }
        if (gesture.equals("press")) {
            int x = Integer.parseInt(args[1]);
            int y = Integer.parseInt(args[2]);
            int hold = args.length > 3 ? Integer.parseInt(args[3]) : 4000;
            report(true, x, y);
            log("tip held at " + x + "," + y + " for " + hold + "ms");
            Thread.sleep(hold);
            report(false, x, y);
            done(500); return;
        }
        if (gesture.equals("drag")) {
            int x0 = LOGICAL_MAX / 4, y0 = LOGICAL_MAX / 2;
            report(true, x0, y0); Thread.sleep(120);
            for (int i = 1; i <= 30; i++) {
                int x = x0 + (LOGICAL_MAX / 2) * i / 30;
                report(true, x, y0);
                Thread.sleep(20);
            }
            report(false, x0 + LOGICAL_MAX / 2, y0);
            done(1500); return;
        }
        done(1500);
    }

    static void tap(int x, int y, int holdMs) throws Exception {
        report(true, x, y);
        log("tip down at " + x + "," + y);
        Thread.sleep(holdMs > 0 ? holdMs : 90);
        report(false, x, y);
        log("tip up");
    }

    static void writeCreate() throws Exception {
        ByteBuffer bb = ByteBuffer.allocate(280 + 4096).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(UHID_CREATE2);
        byte[] name = "srm-uhid-digitizer".getBytes("US-ASCII");
        bb.position(4); bb.put(name);
        bb.position(260); bb.putShort((short) RD.length);
        bb.putShort((short) 0x18);   // bus = BUS_I2C (non-USB/BT => internal => DIRECT touch)
        bb.putInt(0x1234);           // vendor
        bb.putInt(0x9ABC);           // product
        bb.putInt(0);
        bb.putInt(0);
        bb.position(280); bb.put(RD);
        writeAll(bb.array());
    }

    static void report(boolean tip, int x, int y) throws Exception {
        ByteBuffer bb = ByteBuffer.allocate(6 + 5).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(UHID_INPUT2);
        bb.putShort((short) 5);
        bb.put((byte) (tip ? 1 : 0));
        bb.putShort((short) x);
        bb.putShort((short) y);
        writeAll(bb.array());
    }

    static void destroy() throws Exception {
        ByteBuffer bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(UHID_DESTROY);
        writeAll(bb.array());
    }

    static void done(int waitMs) throws Exception {
        Thread.sleep(waitMs);
        destroy();
        log("destroyed, exit");
        Os.close(fd);
    }

    static void writeAll(byte[] data) throws Exception {
        int off = 0;
        while (off < data.length) {
            off += Os.write(fd, data, off, data.length - off);
        }
    }

    static void log(String s) { System.err.println("[uhiddig] " + s); }
}
