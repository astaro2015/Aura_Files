package ru.chitets.app.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.View;
import android.widget.TextView;

final class Ui {
    static final int PAPER = Color.rgb(247, 241, 229);
    static final int PAPER_DARK = Color.rgb(235, 224, 205);
    static final int INK = Color.rgb(39, 35, 29);
    static final int MUTED = Color.rgb(111, 101, 88);
    static final int ACCENT = Color.rgb(156, 79, 46);
    static final int WHITE = Color.WHITE;

    private Ui() {}

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static TextView text(Context context, String value, float sp, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setFontFeatureSettings("kern");
        return view;
    }

    static TextView action(Context context, String value, boolean filled) {
        TextView view = text(context, value, 15, filled ? WHITE : ACCENT);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setGravity(Gravity.CENTER);
        int horizontal = dp(context, 14);
        view.setPadding(horizontal, dp(context, 9), horizontal, dp(context, 9));
        view.setBackground(roundRect(filled ? ACCENT : Color.TRANSPARENT, filled ? ACCENT : 0x559C4F2E, 18, 1));
        view.setClickable(true);
        view.setFocusable(true);
        view.setForeground(context.getDrawable(android.R.drawable.list_selector_background));
        return view;
    }

    static GradientDrawable roundRect(int fill, int stroke, float radiusDp, int strokeDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(radiusDp);
        if (strokeDp > 0) drawable.setStroke(strokeDp, stroke);
        return drawable;
    }

    static void margins(View view, int left, int top, int right, int bottom) {
        if (view.getLayoutParams() instanceof android.view.ViewGroup.MarginLayoutParams) {
            android.view.ViewGroup.MarginLayoutParams params = (android.view.ViewGroup.MarginLayoutParams) view.getLayoutParams();
            params.setMargins(left, top, right, bottom);
            view.setLayoutParams(params);
        }
    }

    /**
     * Android 15 enforces edge-to-edge for targetSdk 35. Keep app chrome clear of
     * the status/navigation bars while still allowing immersive reading to use
     * the whole display when those bars are hidden.
     */
    static void fitSystemBars(Activity activity, View root) {
        final int baseLeft = root.getPaddingLeft();
        final int baseTop = root.getPaddingTop();
        final int baseRight = root.getPaddingRight();
        final int baseBottom = root.getPaddingBottom();

        Window window = activity.getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 28) window.setNavigationBarDividerColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) {
            window.setStatusBarContrastEnforced(false);
            window.setNavigationBarContrastEnforced(false);
        }
        setLightSystemBars(activity, true);

        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int left;
            int top;
            int right;
            int bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                left = bars.left;
                top = bars.top;
                right = bars.right;
                bottom = bars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(baseLeft + left, baseTop + top, baseRight + right, baseBottom + bottom);
            return insets;
        });
        root.requestApplyInsets();
    }

    static void setLightSystemBars(Activity activity, boolean light) {
        View decor = activity.getWindow().getDecorView();
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = decor.getWindowInsetsController();
            if (controller != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(light ? mask : 0, mask);
            }
        } else {
            int flags = decor.getSystemUiVisibility();
            int lightFlags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            decor.setSystemUiVisibility(light ? (flags | lightFlags) : (flags & ~lightFlags));
        }
    }
}
