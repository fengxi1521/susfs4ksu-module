package com.traegateway.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        Gateway.init(context);
        String action = intent == null ? null : intent.getAction();
        Gateway.addLog("[app] 收到广播 " + action);
        if (!Gateway.isAutostart() || !Gateway.isArm64()) {
            return;
        }
        try {
            GatewayService.start(context);
        } catch (Throwable t) {
            Gateway.addLog("[app] 开机自启失败：" + t.getMessage());
        }
    }
}
