package com.traegateway.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

public class MainActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private ExecutorService io;
    private volatile String httpState = "未启动";
    private boolean probeBusy;
    private long lastProbe;
    private String lastLogs = "";

    private View dot;
    private TextView tvStatus;
    private TextView tvHttp;
    private TextView tvPort;
    private TextView tvPid;
    private TextView tvUptime;
    private TextView tvBaseUrl;
    private TextView tvApiKey;
    private TextView tvDataDir;
    private TextView tvLog;
    private TextView tvBattery;
    private TextView tvVersionChip;
    private TextView btnStart;
    private TextView btnStop;
    private TextView btnRestart;
    private Switch switchAutostart;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refresh();
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Gateway.init(getApplicationContext());
        setContentView(R.layout.activity_main);
        bindViews();
        setupActions();

        if (!Gateway.isArm64()) {
            Toast.makeText(this, "当前设备不是 arm64 架构，内置网关无法运行", Toast.LENGTH_LONG).show();
        } else if (!Gateway.isRunning() && !Gateway.isUserStopped()) {
            GatewayService.start(this);
        }
    }

    private void bindViews() {
        dot = findViewById(R.id.dot);
        tvStatus = findViewById(R.id.tv_status);
        tvHttp = findViewById(R.id.tv_http);
        tvPort = findViewById(R.id.tv_port);
        tvPid = findViewById(R.id.tv_pid);
        tvUptime = findViewById(R.id.tv_uptime);
        tvBaseUrl = findViewById(R.id.tv_base_url);
        tvApiKey = findViewById(R.id.tv_api_key);
        tvDataDir = findViewById(R.id.tv_data_dir);
        tvLog = findViewById(R.id.tv_log);
        tvBattery = findViewById(R.id.tv_battery);
        tvVersionChip = findViewById(R.id.tv_version_chip);
        btnStart = findViewById(R.id.btn_start);
        btnStop = findViewById(R.id.btn_stop);
        btnRestart = findViewById(R.id.btn_restart);
        switchAutostart = findViewById(R.id.switch_autostart);

        tvLog.setMovementMethod(new android.text.method.ScrollingMovementMethod());
        tvDataDir.setText("数据目录：" + Gateway.dataDir().getAbsolutePath());

        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            tvVersionChip.setText("v" + info.versionName);
        } catch (Throwable ignored) {
        }

        switchAutostart.setChecked(Gateway.isAutostart());
        switchAutostart.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Gateway.setAutostart(isChecked);
            }
        });
    }

    private void setupActions() {
        io = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "gateway-probe");
                t.setDaemon(true);
                return t;
            }
        });

        btnStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                GatewayService.start(MainActivity.this);
                toast("正在启动网关…");
            }
        });

        btnStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                GatewayService.stop(MainActivity.this);
                toast("已停止网关");
            }
        });

        btnRestart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                GatewayService.restart(MainActivity.this);
                toast("正在重启网关…");
            }
        });

        findViewById(R.id.btn_panel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!Gateway.isRunning()) {
                    GatewayService.start(MainActivity.this);
                }
                startActivity(new Intent(MainActivity.this, PanelActivity.class));
            }
        });

        findViewById(R.id.btn_browser).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!Gateway.isRunning()) {
                    GatewayService.start(MainActivity.this);
                }
                openUrl("http://127.0.0.1:" + Gateway.port() + "/");
            }
        });

        findViewById(R.id.btn_copy_base).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copy("base_url", "http://127.0.0.1:" + Gateway.port() + "/v1");
            }
        });

        findViewById(R.id.btn_copy_key).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copy("api_key", Gateway.apiKey());
            }
        });

        findViewById(R.id.btn_clear_log).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Gateway.clearLogs();
                lastLogs = "";
                tvLog.setText("暂无日志");
            }
        });

        findViewById(R.id.btn_battery).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openBatterySettings();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.removeCallbacks(ticker);
        ui.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(ticker);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacks(ticker);
        if (io != null) {
            io.shutdownNow();
        }
    }

    private void refresh() {
        boolean running = Gateway.isRunning();
        boolean failed = !running && Gateway.getFailures() >= 5 && Gateway.getLastError() != null;
        int port = Gateway.port();

        dot.setBackgroundResource(running ? R.drawable.dot_on : R.drawable.dot_off);
        if (failed) {
            tvStatus.setText("启动失败");
        } else if (running) {
            tvStatus.setText("运行中");
        } else {
            tvStatus.setText(Gateway.isUserStopped() ? "已停止" : "启动中");
        }
        tvStatus.setTextColor(running ? Color.parseColor("#57D6B0")
                : failed ? Color.parseColor("#E0645C") : Color.parseColor("#E8ECF4"));
        tvHttp.setText(failed ? "失败原因：" + Gateway.getLastError()
                : "HTTP 服务：" + httpState + "   ·   127.0.0.1:" + port);
        tvPort.setText(String.valueOf(port));
        tvPid.setText(running && Gateway.getPid() > 0 ? String.valueOf(Gateway.getPid()) : "—");
        tvUptime.setText(running && Gateway.getStartedAt() > 0
                ? duration(System.currentTimeMillis() - Gateway.getStartedAt()) : "—");
        tvBaseUrl.setText("http://127.0.0.1:" + port + "/v1");
        tvApiKey.setText(Gateway.apiKey());

        btnStart.setEnabled(!running);
        btnStart.setAlpha(running ? 0.45f : 1f);
        btnStop.setEnabled(running);
        btnStop.setAlpha(running ? 1f : 0.45f);
        btnRestart.setEnabled(true);

        probe(running);

        String logs = Gateway.logTail(80);
        if (!logs.equals(lastLogs)) {
            lastLogs = logs;
            tvLog.setText(logs.isEmpty() ? "暂无日志" : logs);
            tvLog.post(new Runnable() {
                @Override
                public void run() {
                    if (tvLog.getLayout() != null) {
                        int bottom = tvLog.getLayout().getHeight() - tvLog.getHeight();
                        tvLog.scrollTo(0, Math.max(0, bottom));
                    }
                }
            });
        }

        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            boolean ignoring = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
            tvBattery.setText(ignoring ? "已忽略电池优化，后台更稳定" : "建议关闭电池优化，避免后台被杀");
            tvBattery.setTextColor(ignoring ? Color.parseColor("#57D6B0") : Color.parseColor("#7F899D"));
        } catch (Throwable ignored) {
        }
    }

    private void probe(final boolean running) {
        if (!running) {
            httpState = "未启动";
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (probeBusy || now - lastProbe < 3000) {
            return;
        }
        lastProbe = now;
        probeBusy = true;
        final int port = Gateway.port();
        io.execute(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                HttpURLConnection conn = null;
                try {
                    conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/login").openConnection();
                    conn.setConnectTimeout(1500);
                    conn.setReadTimeout(1500);
                    conn.setRequestMethod("GET");
                    ok = conn.getResponseCode() == 200;
                } catch (Throwable ignored) {
                } finally {
                    if (conn != null) {
                        conn.disconnect();
                    }
                }
                final boolean result = ok;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        httpState = result ? "正常" : "无响应";
                        probeBusy = false;
                    }
                });
            }
        });
    }

    private String duration(long millis) {
        long sec = millis / 1000;
        if (sec < 60) {
            return sec + " 秒";
        }
        long min = sec / 60;
        if (min < 60) {
            return min + " 分 " + (sec % 60) + " 秒";
        }
        long hour = min / 60;
        return hour + " 时 " + (min % 60) + " 分";
    }

    private void copy(String label, String text) {
        ClipboardManager manager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (manager != null) {
            manager.setPrimaryClip(ClipData.newPlainText(label, text));
            toast("已复制：" + text);
        }
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable t) {
            toast("无法打开浏览器，请手动访问 " + url);
        }
    }

    private void openBatterySettings() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                return;
            }
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Throwable t2) {
                toast("请到系统设置中手动关闭电池优化");
            }
        }
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }
}
