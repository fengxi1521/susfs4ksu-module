package com.traegateway.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * 网关进程与状态的统一入口。App 与前台服务共用同一进程，状态保存在静态变量中。
 */
public final class Gateway {

    public static final int DEFAULT_PORT = 7866;
    public static final String DEFAULT_API_KEY = "trae-local-key";

    private static final int MAX_LOG_LINES = 400;
    private static final String TAG = "TraeGateway";

    private static final Object LOCK = new Object();
    private static final ArrayDeque<String> LOGS = new ArrayDeque<String>();
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private static Context context;
    private static Process process;
    private static Process dying;
    private static volatile boolean running;
    private static volatile boolean userStopped;
    private static volatile long startedAt;
    private static volatile int pid = -1;
    private static volatile int failures;
    private static volatile String lastError;

    private Gateway() {
    }

    public static void init(Context c) {
        if (c != null) {
            context = c.getApplicationContext();
        }
    }

    public static boolean isArm64() {
        for (String abi : Build.SUPPORTED_64_BIT_ABIS) {
            if ("arm64-v8a".equals(abi)) {
                return true;
            }
        }
        return false;
    }

    public static File dataDir() {
        File dir = new File(context.getFilesDir(), "gateway");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            Log.w(TAG, "无法创建数据目录 " + dir);
        }
        return dir;
    }

    public static int port() {
        JSONObject cfg = readConfig();
        if (cfg != null) {
            int p = cfg.optInt("port", DEFAULT_PORT);
            if (p > 0 && p < 65536) {
                return p;
            }
        }
        return DEFAULT_PORT;
    }

    public static String apiKey() {
        JSONObject cfg = readConfig();
        if (cfg != null) {
            String k = cfg.optString("api_key", "");
            if (k != null) {
                k = k.trim();
                if (!k.isEmpty()) {
                    return k;
                }
            }
        }
        return DEFAULT_API_KEY;
    }

    public static boolean isRunning() {
        return running;
    }

    public static boolean isUserStopped() {
        return userStopped;
    }

    public static void setUserStopped(boolean value) {
        userStopped = value;
    }

    public static long getStartedAt() {
        return startedAt;
    }

    public static int getPid() {
        return pid;
    }

    public static int getFailures() {
        return failures;
    }

    public static String getLastError() {
        return lastError;
    }

    public static void resetFailures() {
        failures = 0;
    }

    public static boolean start() {
        if (context == null) {
            return false;
        }
        synchronized (LOCK) {
            if (running && process != null) {
                return true;
            }
            userStopped = false;
            if (dying != null) {
                awaitExit(dying, 2500);
                dying = null;
            }
            File bin = resolveBinary();
            if (bin == null) {
                failures++;
                lastError = "找不到可执行的网关二进制（本 App 仅支持 arm64 设备）";
                addLog("[app] " + lastError);
                return false;
            }
            try {
                ProcessBuilder pb = new ProcessBuilder(bin.getAbsolutePath());
                pb.directory(dataDir());
                pb.redirectErrorStream(true);
                pb.environment().put("TRAE_DATA_DIR", dataDir().getAbsolutePath());
                String path = pb.environment().get("PATH");
                if (path == null || path.isEmpty()) {
                    pb.environment().put("PATH", "/system/bin:/system/xbin:/vendor/bin");
                }
                Process p = pb.start();
                process = p;
                running = true;
                pid = pidOf(p);
                startedAt = System.currentTimeMillis();
                lastError = null;
                addLog("[app] 网关进程已启动（pid " + pid + "）");
                Thread pump = new Thread(new PumpTask(p), "gateway-pump");
                pump.setDaemon(true);
                pump.start();
                return true;
            } catch (Throwable t) {
                failures++;
                lastError = "启动失败：" + messageOf(t);
                addLog("[app] " + lastError);
                running = false;
                process = null;
                pid = -1;
                return false;
            }
        }
    }

    public static void stop() {
        Process p;
        synchronized (LOCK) {
            userStopped = true;
            p = process;
            process = null;
            running = false;
            pid = -1;
            failures = 0;
            if (p != null) {
                dying = p;
            }
        }
        if (p != null) {
            addLog("[app] 正在停止网关…");
            try {
                p.destroy();
            } catch (Throwable ignored) {
            }
        }
    }

    public static String logTail(int maxLines) {
        StringBuilder sb = new StringBuilder();
        synchronized (LOGS) {
            int skip = LOGS.size() - maxLines;
            int i = 0;
            for (String line : LOGS) {
                if (i++ < skip) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(line);
            }
        }
        return sb.toString();
    }

    public static void clearLogs() {
        synchronized (LOGS) {
            LOGS.clear();
        }
    }

    public static void addLog(String line) {
        if (line == null) {
            return;
        }
        String entry;
        synchronized (TIME) {
            entry = TIME.format(new Date()) + "  " + line;
        }
        synchronized (LOGS) {
            LOGS.addLast(entry);
            while (LOGS.size() > MAX_LOG_LINES) {
                LOGS.removeFirst();
            }
        }
        Log.i(TAG, line);
    }

    public static boolean isAutostart() {
        return prefs().getBoolean("autostart", true);
    }

    public static void setAutostart(boolean enabled) {
        prefs().edit().putBoolean("autostart", enabled).apply();
    }

    private static SharedPreferences prefs() {
        return context.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    private static JSONObject readConfig() {
        File file = new File(dataDir(), "config.json");
        if (!file.isFile()) {
            return null;
        }
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return new JSONObject(sb.toString());
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * 优先执行 nativeLibraryDir 里的 libtraegateway.so；不可执行时复制到私有目录后 chmod。
     */
    private static File resolveBinary() {
        File nativeLib = null;
        try {
            String dir = context.getApplicationInfo().nativeLibraryDir;
            if (dir != null) {
                nativeLib = new File(dir, "libtraegateway.so");
            }
        } catch (Throwable ignored) {
        }

        if (nativeLib == null || !nativeLib.isFile()) {
            addLog("[app] 未找到内置二进制：" + (nativeLib == null ? "null" : nativeLib.getAbsolutePath()));
        } else if (!executable(nativeLib)) {
            addLog("[app] 内置二进制不可执行，尝试复制到私有目录");
            File copy = copyToPrivate(nativeLib);
            if (copy != null && executable(copy)) {
                return copy;
            }
            addLog("[app] 私有目录副本仍不可执行：" + (copy == null ? "复制失败" : copy.getAbsolutePath()));
        } else {
            return nativeLib;
        }

        File assetCopy = new File(context.getFilesDir(), "bin/trae-gateway");
        if (executable(assetCopy)) {
            return assetCopy;
        }
        return null;
    }

    private static File copyToPrivate(File source) {
        File target = new File(context.getFilesDir(), "bin/trae-gateway");
        if (executable(target) && target.length() == source.length()) {
            return target;
        }
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            return null;
        }
        InputStream in = null;
        OutputStream out = null;
        try {
            in = new FileInputStream(source);
            out = new FileOutputStream(target);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            out.flush();
        } catch (Throwable t) {
            addLog("[app] 复制二进制失败：" + messageOf(t));
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }
        }
        makeExecutable(target);
        return target;
    }

    private static void makeExecutable(File file) {
        try {
            file.setReadable(true, true);
            file.setWritable(true, true);
            file.setExecutable(true, true);
        } catch (Throwable ignored) {
        }
        try {
            Process chmod = new ProcessBuilder("chmod", "755", file.getAbsolutePath()).start();
            chmod.waitFor();
        } catch (Throwable ignored) {
        }
    }

    private static boolean executable(File file) {
        try {
            return file.isFile() && file.length() > 0 && file.canExecute();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void awaitExit(Process p, long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            try {
                p.exitValue();
                return;
            } catch (IllegalThreadStateException alive) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
        try {
            p.destroy();
        } catch (Throwable ignored) {
        }
    }

    private static int pidOf(Process p) {
        try {
            Object value = Process.class.getMethod("pid").invoke(p);
            if (value instanceof Integer) {
                return (Integer) value;
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private static String messageOf(Throwable t) {
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : m;
    }

    private static final class PumpTask implements Runnable {
        private final Process target;

        PumpTask(Process target) {
            this.target = target;
        }

        @Override
        public void run() {
            try {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(target.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    addLog(line);
                }
                reader.close();
            } catch (Throwable ignored) {
            }

            int code = -1;
            try {
                code = target.waitFor();
            } catch (InterruptedException ignored) {
            }

            boolean unexpected;
            synchronized (LOCK) {
                if (process == target) {
                    process = null;
                }
                if (dying == target) {
                    dying = null;
                }
                running = false;
                pid = -1;
                unexpected = !userStopped;
                if (unexpected) {
                    failures++;
                    lastError = "网关进程意外退出（code=" + code + "）";
                } else {
                    failures = 0;
                }
            }
            addLog("[app] 网关进程已退出（code=" + code + "）");
            if (unexpected) {
                addLog("[app] 将在后台自动重启…");
            }
        }
    }
}
