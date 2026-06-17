import android.system.Os;
import android.system.OsConstants;
import java.io.FileDescriptor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Verification spike: create a virtual HID mouse via /dev/uhid (write-based protocol,
 * no ioctl) and emit pointer reports. Run as shell via app_process to mirror the
 * Shizuku (shell-domain) runtime of the real app.
 *
 * Usage: UhidSpike <wiggle|drag|click> [holdMs]
 */
public class UhidSpike {

    // uhid event types
    static final int UHID_CREATE2 = 11;
    static final int UHID_DESTROY = 1;
    static final int UHID_INPUT2  = 12;

    // Standard 3-byte relative mouse: [buttons, dx, dy]
    static final byte[] RD = new byte[] {
        0x05,0x01, 0x09,0x02, (byte)0xA1,0x01,
        0x09,0x01, (byte)0xA1,0x00,
        0x05,0x09, 0x19,0x01, 0x29,0x03,
        0x15,0x00, 0x25,0x01, (byte)0x95,0x03, 0x75,0x01, (byte)0x81,0x02,
        (byte)0x95,0x01, 0x75,0x05, (byte)0x81,0x03,
        0x05,0x01, 0x09,0x30, 0x09,0x31,
        0x15,(byte)0x81, 0x25,0x7F, 0x75,0x08, (byte)0x95,0x02, (byte)0x81,0x06,
        (byte)0xC0, (byte)0xC0
    };

    static FileDescriptor fd;

    public static void main(String[] args) throws Exception {
        String gesture = args.length > 0 ? args[0] : "wiggle";
        int holdMs = args.length > 1 ? Integer.parseInt(args[1]) : 4000;

        fd = Os.open("/dev/uhid", OsConstants.O_RDWR, 0);
        log("opened /dev/uhid");

        // drain reader thread (kernel pushes START/OPEN/OUTPUT events)
        Thread reader = new Thread(() -> {
            byte[] b = new byte[4380];
            try {
                while (true) {
                    int n = Os.read(fd, b, 0, b.length);
                    if (n <= 0) break;
                    int type = b[0] & 0xFF;
                    log("kernel event type=" + type);
                }
            } catch (Exception e) { /* fd closed */ }
        });
        reader.setDaemon(true);
        reader.start();

        writeCreate();
        log("CREATE2 written, waiting for OPEN...");
        Thread.sleep(700);

        log("gesture=" + gesture);
        if (gesture.equals("clickat")) {
            int tx = Integer.parseInt(args[2]);
            int ty = Integer.parseInt(args[3]);
            for (int i = 0; i < 50; i++) { report(0, -127, -127); Thread.sleep(4); } // home to (0,0)
            Thread.sleep(150);
            moveBy(tx, ty);                              // relative move to target
            Thread.sleep(250);
            report(1, 0, 0); Thread.sleep(90);           // click
            report(0, 0, 0);
            log("clicked at " + tx + "," + ty);
            Thread.sleep(holdMs); destroy(); log("destroyed, exit"); Os.close(fd); return;
        }
        if (gesture.equals("press")) {
            for (int i = 0; i < 50; i++) { report(0, -127, -127); Thread.sleep(4); }
            Thread.sleep(150);
            moveBy(1440, 900);
            Thread.sleep(250);
            report(1, 0, 0);
            log("button held at center");
            Thread.sleep(holdMs);
            report(0, 0, 0);
            destroy(); Os.close(fd); return;
        }
        switch (gesture) {
            case "wiggle":
                for (int i = 0; i < 40; i++) {
                    report(0, (i % 2 == 0) ? 12 : -12, 6);
                    Thread.sleep(25);
                }
                break;
            case "click":
                report(1, 0, 0); Thread.sleep(60);
                report(0, 0, 0);
                break;
            case "drag":
                report(1, 0, 0); Thread.sleep(80);          // left button down
                for (int i = 0; i < 30; i++) {               // drag down-right
                    report(1, 6, 10);
                    Thread.sleep(20);
                }
                report(0, 0, 0);                             // release
                break;
            case "pantest":
                for (int i = 0; i < 40; i++) { report(0, -127, -127); Thread.sleep(5); } // home to (0,0)
                for (int i = 0; i < 12; i++) { report(0, 120, 75); Thread.sleep(8); }     // to ~center
                Thread.sleep(300);
                report(1, 0, 0); Thread.sleep(150);          // left button down (space held externally)
                for (int i = 0; i < 25; i++) { report(1, 10, 16); Thread.sleep(22); }     // drag down-right
                report(0, 0, 0);                             // release
                break;
        }

        log("holding device alive " + holdMs + "ms");
        Thread.sleep(holdMs);

        destroy();
        log("destroyed, exit");
        Os.close(fd);
    }

    static void writeCreate() throws Exception {
        ByteBuffer bb = ByteBuffer.allocate(280 + 4096).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(UHID_CREATE2);                 // type @0
        byte[] name = "srm-uhid-mouse".getBytes("US-ASCII");
        bb.position(4); bb.put(name);            // name @4 (128)
        bb.position(260); bb.putShort((short) RD.length); // rd_size
        bb.putShort((short) 0x03);               // bus = BUS_USB
        bb.putInt(0x1234);                       // vendor
        bb.putInt(0x5678);                       // product
        bb.putInt(0);                            // version
        bb.putInt(0);                            // country
        bb.position(280); bb.put(RD);            // rd_data
        writeAll(bb.array());
    }

    static void moveBy(int tx, int ty) throws Exception {
        int x = 0, y = 0;
        while (x != tx || y != ty) {
            int sx = Math.max(-120, Math.min(120, tx - x));
            int sy = Math.max(-120, Math.min(120, ty - y));
            report(0, sx, sy);
            x += sx; y += sy;
            Thread.sleep(8);
        }
    }

    static void report(int buttons, int dx, int dy) throws Exception {
        ByteBuffer bb = ByteBuffer.allocate(6 + 3).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(UHID_INPUT2);                  // type
        bb.putShort((short) 3);                  // size = 3-byte report
        bb.put((byte) buttons);
        bb.put((byte) dx);
        bb.put((byte) dy);
        writeAll(bb.array());
    }

    static void destroy() throws Exception {
        ByteBuffer bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(UHID_DESTROY);
        writeAll(bb.array());
    }

    static void writeAll(byte[] data) throws Exception {
        int off = 0;
        while (off < data.length) {
            off += Os.write(fd, data, off, data.length - off);
        }
    }

    static void log(String s) { System.err.println("[uhidspike] " + s); }
}
