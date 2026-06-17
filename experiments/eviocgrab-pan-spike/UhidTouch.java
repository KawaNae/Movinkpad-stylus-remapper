import android.system.Os;
import android.system.OsConstants;
import java.io.FileDescriptor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Verification spike #3: Win8-style MULTITOUCH digitizer via /dev/uhid.
 * Unlike the single-touch spike, this carries Contact Identifier / Contact Count
 * / Contact Count Maximum (Feature), so the kernel binds hid-multitouch, which
 * sets INPUT_PROP_DIRECT. Android then treats it as a real DIRECT touchscreen
 * (not a POINTER/MOUSE touchpad) and delivers touch events to the UI.
 *
 * Requires answering UHID_GET_REPORT (contact-count-max feature) during probe.
 *
 * Input report (no report id), 7 bytes:
 *   [tip(1)+pad(7)] [contactId u8] [X u16 LE] [Y u16 LE] [contactCount u8]
 *
 * Usage:
 *   UhidTouch center [holdMs]
 *   UhidTouch tap <X> <Y> [holdMs]                 (X,Y in 0..32767)
 *   UhidTouch tappx <PX> <PY> <W> <H> [holdMs]
 *   UhidTouch press <X> <Y> [holdMs]               (hold tip down)
 *   UhidTouch drag
 */
public class UhidTouch {

    static final int UHID_CREATE2 = 11;
    static final int UHID_DESTROY = 1;
    static final int UHID_INPUT2  = 12;
    static final int UHID_GET_REPORT = 9;
    static final int UHID_GET_REPORT_REPLY = 10;
    static final int UHID_SET_REPORT = 13;
    static final int UHID_SET_REPORT_REPLY = 14;

    static final int LOGICAL_MAX = 32767;
    static final int MAX_CONTACTS = 10;

    static final byte[] RD = new byte[] {
        0x05, 0x0D,             // Usage Page (Digitizer)
        0x09, 0x04,             // Usage (Touch Screen)
        (byte)0xA1, 0x01,       // Collection (Application)
        0x09, 0x22,             //   Usage (Finger)
        (byte)0xA1, 0x02,       //   Collection (Logical)
        0x09, 0x42,             //     Usage (Tip Switch)
        0x15, 0x00,             //     Logical Minimum (0)
        0x25, 0x01,             //     Logical Maximum (1)
        0x75, 0x01,             //     Report Size (1)
        (byte)0x95, 0x01,       //     Report Count (1)
        (byte)0x81, 0x02,       //     Input (Data,Var,Abs)
        0x75, 0x07,             //     Report Size (7)
        (byte)0x95, 0x01,       //     Report Count (1)
        (byte)0x81, 0x03,       //     Input (Cnst)  -- pad
        0x75, 0x08,             //     Report Size (8)
        0x09, 0x51,             //     Usage (Contact Identifier)
        (byte)0x95, 0x01,       //     Report Count (1)
        0x15, 0x00,             //     Logical Minimum (0)
        0x25, 0x7F,             //     Logical Maximum (127)
        (byte)0x81, 0x02,       //     Input (Data,Var,Abs)
        0x05, 0x01,             //     Usage Page (Generic Desktop)
        0x15, 0x00,             //     Logical Minimum (0)
        0x26, (byte)0xFF, 0x7F, //     Logical Maximum (32767)
        0x75, 0x10,             //     Report Size (16)
        (byte)0x95, 0x01,       //     Report Count (1)
        0x09, 0x30,             //     Usage (X)
        (byte)0x81, 0x02,       //     Input (Data,Var,Abs)
        0x09, 0x31,             //     Usage (Y)
        (byte)0x81, 0x02,       //     Input (Data,Var,Abs)
        (byte)0xC0,             //   End Collection
        0x05, 0x0D,             //   Usage Page (Digitizer)
        0x09, 0x54,             //   Usage (Contact Count)
        (byte)0x95, 0x01,       //   Report Count (1)
        0x75, 0x08,             //   Report Size (8)
        0x15, 0x00,             //   Logical Minimum (0)
        0x25, 0x7F,             //   Logical Maximum (127)
        (byte)0x81, 0x02,       //   Input (Data,Var,Abs)
        0x09, 0x55,             //   Usage (Contact Count Maximum)
        0x25, 0x0A,             //   Logical Maximum (10)
        0x75, 0x08,             //   Report Size (8)
        (byte)0x95, 0x01,       //   Report Count (1)
        (byte)0xB1, 0x02,       //   Feature (Data,Var,Abs)
        (byte)0xC0              // End Collection
    };

    static FileDescriptor fd;

    public static void main(String[] args) throws Exception {
        String gesture = args.length > 0 ? args[0] : "center";

        fd = Os.open("/dev/uhid", OsConstants.O_RDWR, 0);
        log("opened /dev/uhid");

        Thread reader = new Thread(UhidTouch::readLoop);
        reader.setDaemon(true);
        reader.start();

        writeCreate();
        log("CREATE2 written, waiting for probe/OPEN...");
        Thread.sleep(1000);
        log("gesture=" + gesture);

        if (gesture.equals("center")) {
            int hold = args.length > 1 ? Integer.parseInt(args[1]) : 0;
            tap(LOGICAL_MAX / 2, LOGICAL_MAX / 2, hold);
            done(2000); return;
        }
        if (gesture.equals("tap")) {
            int x = Integer.parseInt(args[1]), y = Integer.parseInt(args[2]);
            int hold = args.length > 3 ? Integer.parseInt(args[3]) : 0;
            tap(x, y, hold);
            done(2000); return;
        }
        if (gesture.equals("tappx")) {
            int px = Integer.parseInt(args[1]), py = Integer.parseInt(args[2]);
            int w = Integer.parseInt(args[3]), h = Integer.parseInt(args[4]);
            int hold = args.length > 5 ? Integer.parseInt(args[5]) : 0;
            int x = (int)((long)px * LOGICAL_MAX / w), y = (int)((long)py * LOGICAL_MAX / h);
            log("pixel " + px + "," + py + " -> abs " + x + "," + y);
            tap(x, y, hold);
            done(2000); return;
        }
        if (gesture.equals("press")) {
            int x = Integer.parseInt(args[1]), y = Integer.parseInt(args[2]);
            int hold = args.length > 3 ? Integer.parseInt(args[3]) : 4000;
            report(true, x, y, 1);
            log("tip held at " + x + "," + y + " for " + hold + "ms");
            Thread.sleep(hold);
            report(false, x, y, 0);
            done(500); return;
        }
        if (gesture.equals("twodrag")) {
            // two-finger parallel translation = pan. args: x0 y0 x1 y1 dx dy [steps] [stepMs]
            int x0 = Integer.parseInt(args[1]), y0 = Integer.parseInt(args[2]);
            int x1 = Integer.parseInt(args[3]), y1 = Integer.parseInt(args[4]);
            int dx = Integer.parseInt(args[5]), dy = Integer.parseInt(args[6]);
            int steps  = args.length > 7 ? Integer.parseInt(args[7]) : 25;
            int stepMs = args.length > 8 ? Integer.parseInt(args[8]) : 30;
            // initial down (both contacts)
            contact(true, 0, x0, y0, 2); contact(true, 1, x1, y1, 2);
            Thread.sleep(150);
            for (int i = 1; i <= steps; i++) {
                int ax = x0 + dx * i / steps, ay = y0 + dy * i / steps;
                int bx = x1 + dx * i / steps, by = y1 + dy * i / steps;
                contact(true, 0, ax, ay, 2); contact(true, 1, bx, by, 2);
                Thread.sleep(stepMs);
            }
            Thread.sleep(80);
            contact(false, 0, x0 + dx, y0 + dy, 0); contact(false, 1, x1 + dx, y1 + dy, 0);
            log("twodrag done");
            done(1500); return;
        }
        if (gesture.equals("dragxy")) {
            int x0 = Integer.parseInt(args[1]), y0 = Integer.parseInt(args[2]);
            int x1 = Integer.parseInt(args[3]), y1 = Integer.parseInt(args[4]);
            int steps = args.length > 5 ? Integer.parseInt(args[5]) : 30;
            int stepMs = args.length > 6 ? Integer.parseInt(args[6]) : 25;
            report(true, x0, y0, 1); Thread.sleep(150);
            for (int i = 1; i <= steps; i++) {
                int x = x0 + (x1 - x0) * i / steps;
                int y = y0 + (y1 - y0) * i / steps;
                report(true, x, y, 1);
                Thread.sleep(stepMs);
            }
            Thread.sleep(80);
            report(false, x1, y1, 0);
            log("dragxy " + x0 + "," + y0 + " -> " + x1 + "," + y1);
            done(1500); return;
        }
        if (gesture.equals("drag")) {
            int x0 = LOGICAL_MAX / 4, y = LOGICAL_MAX / 2;
            report(true, x0, y, 1); Thread.sleep(120);
            for (int i = 1; i <= 30; i++) {
                report(true, x0 + (LOGICAL_MAX / 2) * i / 30, y, 1);
                Thread.sleep(20);
            }
            report(false, x0 + LOGICAL_MAX / 2, y, 0);
            done(1500); return;
        }
        done(1500);
    }

    static void readLoop() {
        byte[] b = new byte[4380];
        try {
            while (true) {
                int n = Os.read(fd, b, 0, b.length);
                if (n <= 0) break;
                ByteBuffer in = ByteBuffer.wrap(b, 0, n).order(ByteOrder.LITTLE_ENDIAN);
                int type = in.getInt(0);
                log("kernel event type=" + type);
                if (type == UHID_GET_REPORT) {
                    int id = in.getInt(4);
                    int rnum = b[8] & 0xFF;
                    log("GET_REPORT id=" + id + " rnum=" + rnum + " -> reply maxContacts");
                    replyGetReport(id, new byte[] { (byte) MAX_CONTACTS });
                } else if (type == UHID_SET_REPORT) {
                    int id = in.getInt(4);
                    replySetReport(id);
                }
            }
        } catch (Exception e) { /* fd closed */ }
    }

    static void tap(int x, int y, int holdMs) throws Exception {
        report(true, x, y, 1);
        log("tip down at " + x + "," + y);
        Thread.sleep(holdMs > 0 ? holdMs : 90);
        report(false, x, y, 0);
        log("tip up");
    }

    static void writeCreate() throws Exception {
        ByteBuffer bb = ByteBuffer.allocate(280 + 4096).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(UHID_CREATE2);
        byte[] name = "srm-uhid-touch".getBytes("US-ASCII");
        bb.position(4); bb.put(name);
        bb.position(260); bb.putShort((short) RD.length);
        bb.putShort((short) 0x18);   // bus = BUS_I2C (internal => DIRECT)
        bb.putInt(0x1234);           // vendor
        bb.putInt(0xDEF0);           // product
        bb.putInt(0);
        bb.putInt(0);
        bb.position(280); bb.put(RD);
        writeAll(bb.array());
    }

    static void report(boolean tip, int x, int y, int count) throws Exception {
        contact(tip, 0, x, y, count);
    }

    // report: [tip+pad][contactId][X u16][Y u16][contactCount]
    static void contact(boolean tip, int id, int x, int y, int count) throws Exception {
        byte[] r = new byte[7];
        r[0] = (byte) (tip ? 1 : 0);
        r[1] = (byte) id;               // contact id
        r[2] = (byte) (x & 0xFF);
        r[3] = (byte) ((x >> 8) & 0xFF);
        r[4] = (byte) (y & 0xFF);
        r[5] = (byte) ((y >> 8) & 0xFF);
        r[6] = (byte) count;
        ByteBuffer bb = ByteBuffer.allocate(6 + r.length).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(UHID_INPUT2);
        bb.putShort((short) r.length);
        bb.put(r);
        writeAll(bb.array());
    }

    static void replyGetReport(int id, byte[] data) throws Exception {
        ByteBuffer bb = ByteBuffer.allocate(12 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(UHID_GET_REPORT_REPLY);  // type @0
        bb.putInt(id);                     // id @4
        bb.putShort((short) 0);            // err @8
        bb.putShort((short) data.length);  // size @10
        bb.put(data);                      // data @12
        writeAll(bb.array());
    }

    static void replySetReport(int id) throws Exception {
        ByteBuffer bb = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(UHID_SET_REPORT_REPLY);
        bb.putInt(id);
        bb.putShort((short) 0);            // err
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

    static void log(String s) { System.err.println("[uhidtouch] " + s); }
}
