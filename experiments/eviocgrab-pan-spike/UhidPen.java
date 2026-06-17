import android.system.Os;
import android.system.OsConstants;
import java.io.FileDescriptor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Verification spike #4: virtual STYLUS (pen) digitizer via /dev/uhid.
 * Pen tip-down == left click in CSP. Combined with a held Space (hand tool),
 * a pen drag should PAN the canvas (mouse/stylus drag honors Space; finger
 * touch does not). This mirrors the Issue's "left click + space" model.
 *
 * Report (5 bytes, no report id):
 *   byte0: bit0=Tip Switch, bit1=In Range, bits2-7 pad
 *   byte1-2: X u16 LE   byte3-4: Y u16 LE
 *
 * Usage:
 *   UhidPen hover <X> <Y> [ms]
 *   UhidPen click <X> <Y> [holdMs]
 *   UhidPen pendrag <X0> <Y0> <X1> <Y1> [steps] [stepMs]
 */
public class UhidPen {

    static final int UHID_CREATE2 = 11;
    static final int UHID_DESTROY = 1;
    static final int UHID_INPUT2  = 12;
    static final int UHID_GET_REPORT = 9;
    static final int UHID_GET_REPORT_REPLY = 10;
    static final int UHID_SET_REPORT = 13;
    static final int UHID_SET_REPORT_REPLY = 14;

    static final int LOGICAL_MAX = 32767;

    static final byte[] RD = new byte[] {
        0x05, 0x0D,             // Usage Page (Digitizer)
        0x09, 0x02,             // Usage (Pen)
        (byte)0xA1, 0x01,       // Collection (Application)
        0x09, 0x20,             //   Usage (Stylus)
        (byte)0xA1, 0x00,       //   Collection (Physical)
        0x09, 0x42,             //     Usage (Tip Switch)
        0x09, 0x32,             //     Usage (In Range)
        0x15, 0x00,             //     Logical Min (0)
        0x25, 0x01,             //     Logical Max (1)
        0x75, 0x01,             //     Report Size (1)
        (byte)0x95, 0x02,       //     Report Count (2)
        (byte)0x81, 0x02,       //     Input (Data,Var,Abs)
        (byte)0x95, 0x06,       //     Report Count (6)
        (byte)0x81, 0x03,       //     Input (Cnst)  -- pad
        0x05, 0x01,             //     Usage Page (Generic Desktop)
        0x09, 0x30,             //     Usage (X)
        0x09, 0x31,             //     Usage (Y)
        0x16, 0x00, 0x00,       //     Logical Min (0)
        0x26, (byte)0xFF, 0x7F, //     Logical Max (32767)
        0x75, 0x10,             //     Report Size (16)
        (byte)0x95, 0x02,       //     Report Count (2)
        (byte)0x81, 0x02,       //     Input (Data,Var,Abs)
        (byte)0xC0,             //   End Collection
        (byte)0xC0              // End Collection
    };

    static FileDescriptor fd;

    public static void main(String[] args) throws Exception {
        String gesture = args.length > 0 ? args[0] : "hover";

        fd = Os.open("/dev/uhid", OsConstants.O_RDWR, 0);
        log("opened /dev/uhid");

        Thread reader = new Thread(UhidPen::readLoop);
        reader.setDaemon(true);
        reader.start();

        writeCreate();
        log("CREATE2 written, waiting for OPEN...");
        Thread.sleep(1000);
        log("gesture=" + gesture);

        if (gesture.equals("hover")) {
            int x = Integer.parseInt(args[1]), y = Integer.parseInt(args[2]);
            int ms = args.length > 3 ? Integer.parseInt(args[3]) : 2000;
            pen(false, true, x, y);   // in range, no tip
            log("hovering at " + x + "," + y);
            Thread.sleep(ms);
            pen(false, false, x, y);  // out of range
            done(500); return;
        }
        if (gesture.equals("click")) {
            int x = Integer.parseInt(args[1]), y = Integer.parseInt(args[2]);
            int hold = args.length > 3 ? Integer.parseInt(args[3]) : 90;
            pen(false, true, x, y); Thread.sleep(60);   // in range
            pen(true,  true, x, y);                      // tip down = left click
            log("tip down (click) at " + x + "," + y);
            Thread.sleep(hold);
            pen(false, true, x, y); Thread.sleep(40);    // tip up
            pen(false, false, x, y);                     // out of range
            done(800); return;
        }
        if (gesture.equals("pendrag")) {
            int x0 = Integer.parseInt(args[1]), y0 = Integer.parseInt(args[2]);
            int x1 = Integer.parseInt(args[3]), y1 = Integer.parseInt(args[4]);
            int steps  = args.length > 5 ? Integer.parseInt(args[5]) : 25;
            int stepMs = args.length > 6 ? Integer.parseInt(args[6]) : 30;
            pen(false, true, x0, y0); Thread.sleep(80);  // in range (hover)
            pen(true,  true, x0, y0); Thread.sleep(120); // tip down = click
            for (int i = 1; i <= steps; i++) {
                int x = x0 + (x1 - x0) * i / steps;
                int y = y0 + (y1 - y0) * i / steps;
                pen(true, true, x, y);
                Thread.sleep(stepMs);
            }
            Thread.sleep(80);
            pen(false, true, x1, y1); Thread.sleep(40);  // tip up
            pen(false, false, x1, y1);                   // out of range
            log("pendrag " + x0 + "," + y0 + " -> " + x1 + "," + y1);
            done(1200); return;
        }
        done(1000);
    }

    static void readLoop() {
        byte[] b = new byte[4380];
        try {
            while (true) {
                int n = Os.read(fd, b, 0, b.length);
                if (n <= 0) break;
                int type = ByteBuffer.wrap(b, 0, n).order(ByteOrder.LITTLE_ENDIAN).getInt(0);
                log("kernel event type=" + type);
                if (type == UHID_GET_REPORT) {
                    int id = ByteBuffer.wrap(b, 0, n).order(ByteOrder.LITTLE_ENDIAN).getInt(4);
                    replyGetReport(id, new byte[] { 0 });
                } else if (type == UHID_SET_REPORT) {
                    int id = ByteBuffer.wrap(b, 0, n).order(ByteOrder.LITTLE_ENDIAN).getInt(4);
                    replySetReport(id);
                }
            }
        } catch (Exception e) { }
    }

    static void writeCreate() throws Exception {
        ByteBuffer bb = ByteBuffer.allocate(280 + 4096).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(UHID_CREATE2);
        byte[] name = "srm-uhid-pen".getBytes("US-ASCII");
        bb.position(4); bb.put(name);
        bb.position(260); bb.putShort((short) RD.length);
        bb.putShort((short) 0x18);   // bus = BUS_I2C
        bb.putInt(0x1234);           // vendor
        bb.putInt(0xBEEF);           // product
        bb.putInt(0);
        bb.putInt(0);
        bb.position(280); bb.put(RD);
        writeAll(bb.array());
    }

    // byte0: bit0 tip, bit1 in-range; X u16; Y u16
    static void pen(boolean tip, boolean inRange, int x, int y) throws Exception {
        byte[] r = new byte[5];
        r[0] = (byte) ((tip ? 1 : 0) | (inRange ? 2 : 0));
        r[1] = (byte) (x & 0xFF);
        r[2] = (byte) ((x >> 8) & 0xFF);
        r[3] = (byte) (y & 0xFF);
        r[4] = (byte) ((y >> 8) & 0xFF);
        ByteBuffer bb = ByteBuffer.allocate(6 + r.length).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(UHID_INPUT2);
        bb.putShort((short) r.length);
        bb.put(r);
        writeAll(bb.array());
    }

    static void replyGetReport(int id, byte[] data) throws Exception {
        ByteBuffer bb = ByteBuffer.allocate(12 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(UHID_GET_REPORT_REPLY);
        bb.putInt(id);
        bb.putShort((short) 0);
        bb.putShort((short) data.length);
        bb.put(data);
        writeAll(bb.array());
    }

    static void replySetReport(int id) throws Exception {
        ByteBuffer bb = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(UHID_SET_REPORT_REPLY);
        bb.putInt(id);
        bb.putShort((short) 0);
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
        while (off < data.length) off += Os.write(fd, data, off, data.length - off);
    }

    static void log(String s) { System.err.println("[uhidpen] " + s); }
}
