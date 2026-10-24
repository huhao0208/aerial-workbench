package app.orchard.tv.system;

import android.content.Context;
import android.os.Looper;
import dalvik.system.PathClassLoader;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Timer;
import java.util.TimerTask;

/** Device-only root adapter. No socket, shell commands, embedded vendor key or boot service. */
public final class TclBacklightHelper {
    public static int nextLevel(int current, int direction) {
        if (current < 1 || current > 100 || (direction != -1 && direction != 1)) {
            throw new IllegalArgumentException();
        }
        int next = direction > 0 ? (current / 5 + 1) * 5 : ((current + 4) / 5 - 1) * 5;
        return Math.max(1, Math.min(100, next));
    }

    public static void main(String[] args) {
        Timer idle = new Timer(true);
        TimerTask expiry = new TimerTask() { @Override public void run() { System.exit(0); } };
        idle.schedule(expiry, 20_000);
        try {
            if (android.os.Process.myUid() != 0) throw new IllegalStateException();
            if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
            Class<?> threadClass = Class.forName("android.app.ActivityThread");
            Object thread = threadClass.getMethod("systemMain").invoke(null);
            Context context = (Context) threadClass.getMethod("getSystemContext").invoke(thread);
            String settingsApk = context.getPackageManager().getApplicationInfo("com.tcl.settings", 0).sourceDir;
            ClassLoader loader = new PathClassLoader(settingsApk, ClassLoader.getSystemClassLoader());
            String key = (String) loader.loadClass("com.tcl.settings.middleware.data.ConfigConst")
                .getField("TSA_KEY").get(null);
            Class<?> function = Class.forName("com.tcl.tvmanager.TTvFunctionManager");
            Object fm = function.getMethod("getInstance", Context.class).invoke(null, context);
            if (!Boolean.TRUE.equals(function.getMethod("systemAuthorized", String.class).invoke(fm, key))) {
                throw new IllegalStateException();
            }
            Class<?> picture = Class.forName("com.tcl.tvmanager.TTvPictureManager");
            Object pm = picture.getMethod("getInstance", Context.class).invoke(null, context);
            Class<?> effect = Class.forName("com.tcl.tvmanager.vo.EnTCLPqSpecialEffect");
            Object lowBlue = enumValue(effect, "EN_PQ_EFFECT_LOW_BLUE_RAY");
            Object restoration = enumValue(effect, "EN_PQ_EFFECT_PQ_ENGINE_CAM");
            BufferedReader input = new BufferedReader(new InputStreamReader(System.in));
            while (true) {
                expiry.cancel();
                expiry = new TimerTask() { @Override public void run() { System.exit(0); } };
                idle.schedule(expiry, 12_000);
                idle.purge();
                // All callers are local and root-authorized; still bound every protocol line.
                StringBuilder line = new StringBuilder();
                int ch;
                while ((ch = input.read()) != -1 && ch != '\n') {
                    if (line.length() >= 64) throw new IllegalArgumentException();
                    line.append((char) ch);
                }
                if (ch == -1 || line.toString().equals("EXIT")) break;
                String command = line.toString();
                int current = (Integer) picture.getMethod("getGraphicBacklight").invoke(pm);
                if (current < 1 || current > 100) throw new IllegalStateException();
                int next = current;
                if (command.equals("STEP 1")) next = nextLevel(current, 1);
                else if (command.equals("STEP -1")) next = nextLevel(current, -1);
                else if (command.startsWith("SET ") && command.matches("SET [0-9]{1,3}")) {
                    next = Integer.parseInt(command.substring(4));
                    if (next < 1 || next > 100) throw new IllegalArgumentException();
                } else if (command.equals("EYE 0") || command.equals("EYE 1")) {
                    if ((Integer) picture.getMethod("getPqSpecialEffect", effect).invoke(pm, restoration) == 1) {
                        throw new IllegalStateException();
                    }
                    int value = command.endsWith("1") ? 1 : 0;
                    if (!Boolean.TRUE.equals(picture.getMethod("setPqSpecialEffect", effect, int.class, int.class)
                        .invoke(pm, lowBlue, value, 2))) throw new IllegalStateException();
                    if ((Integer) picture.getMethod("getPqSpecialEffect", effect).invoke(pm, lowBlue) != value) {
                        throw new IllegalStateException();
                    }
                } else if (!command.equals("GET")) throw new IllegalArgumentException();
                if (next != current && !Boolean.TRUE.equals(picture.getMethod("setGraphicBacklight", int.class).invoke(pm, next))) {
                    throw new IllegalStateException();
                }
                int actual = (Integer) picture.getMethod("getGraphicBacklight").invoke(pm);
                if (actual < 1 || actual > 100 || (!command.startsWith("EYE ") && actual != next)) throw new IllegalStateException();
                int eye = (Integer) picture.getMethod("getPqSpecialEffect", effect).invoke(pm, lowBlue);
                if (eye != 0 && eye != 1) eye = -1;
                if ((Integer) picture.getMethod("getPqSpecialEffect", effect).invoke(pm, restoration) == 1) eye = 2;
                System.out.println("WB-BACKLIGHT OK " + actual + " " + eye);
                System.out.flush();
            }
        } catch (Throwable failure) {
            // Never log the vendor authorization material or substitute a guessed brightness.
            System.out.println("WB-BACKLIGHT ERR");
            System.out.flush();
        } finally {
            idle.cancel();
            // TCL initializes worker threads. EOF/idle must end those too.
            System.exit(0);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumValue(Class<?> type, String value) {
        return Enum.valueOf((Class) type, value);
    }
}
