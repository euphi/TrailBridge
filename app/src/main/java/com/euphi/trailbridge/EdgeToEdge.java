package com.euphi.trailbridge;

import android.app.Activity;
import android.view.View;

import androidx.annotation.IdRes;
import androidx.annotation.LayoutRes;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Edge to edge on every API level (targetSdk 35 forces it anyway): the dark window background
 * runs behind the system bars, the scroll view keeps clear of them (and of the keyboard, to scroll
 * a text field into view).
 */
final class EdgeToEdge {

    private EdgeToEdge() {
    }

    /** setContentView, then pads the view with id {@code scrollId} by the system-bar insets. */
    static void setContentView(Activity a, @LayoutRes int layout, @IdRes int scrollId) {
        WindowCompat.setDecorFitsSystemWindows(a.getWindow(), false);
        a.setContentView(layout);
        View scroll = a.findViewById(scrollId);
        ViewCompat.setOnApplyWindowInsetsListener(scroll, (v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            return WindowInsetsCompat.CONSUMED;
        });
    }
}
