package com.tcl.tshop.activitys;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Routes the firmware's shopping launch to the owner's configured action. */
public final class WelcomeActivity extends Activity {
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        try {
            startActivity(new Intent().setClassName("app.hzf.aerial", "app.orchard.tv.shortcuts.ShoppingShortcutActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (android.content.ActivityNotFoundException absent) {
            // Removing the private launcher leaves no background activity or service behind.
        } finally { finish(); }
    }
}
