package io.github.liangjiahui01.moor;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small view builders shared by every screen. All UI is built in code. */
final class Ui {
    private Ui() {}

    static final int REGULAR = 0, MEDIUM = 1, SEMIBOLD = 2;
    private static Typeface[] faces;
    private static Typeface mono, monoBold;

    static Typeface face(Context c, int weight) {
        if (faces == null) {
            faces = new Typeface[3];
            String[] files = {"Geist-Regular.ttf", "Geist-Medium.ttf", "Geist-SemiBold.ttf"};
            for (int i = 0; i < 3; i++) {
                try {
                    faces[i] = Typeface.createFromAsset(c.getAssets(), "ui-fonts/" + files[i]);
                } catch (RuntimeException e) {
                    faces[i] = i == SEMIBOLD ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT;
                }
            }
        }
        return faces[weight];
    }

    /**
     * Terminal font, as in Ghostty: JetBrains Mono, then symbol fonts (Noto Sans Symbols 2 without the characters
     * that default to emoji, and Symbols Nerd Font for icons), then the system fonts for CJK and emoji.
     */
    static Typeface mono(Context c) {
        if (mono == null) mono = termFace(c, "JetBrainsMono-Regular.ttf", false);
        return mono;
    }

    static Typeface monoBold(Context c) {
        if (monoBold == null) monoBold = termFace(c, "JetBrainsMono-Bold.ttf", true);
        return monoBold;
    }

    private static Typeface termFace(Context c, String file, boolean bold) {
        android.content.res.AssetManager am = c.getAssets();
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            try {
                android.graphics.fonts.FontFamily main = new android.graphics.fonts.FontFamily.Builder(
                    new android.graphics.fonts.Font.Builder(am, "term-fonts/" + file).build()).build();
                Typeface.CustomFallbackBuilder b = new Typeface.CustomFallbackBuilder(main);
                for (String f : new String[]{"NotoSansSymbols2-Text.ttf", "SymbolsNerdFontMono-Regular.ttf"}) {
                    b.addCustomFallback(new android.graphics.fonts.FontFamily.Builder(
                        new android.graphics.fonts.Font.Builder(am, "term-fonts/" + f).build()).build());
                }
                return b.setSystemFallback("sans-serif")
                    .setStyle(new android.graphics.fonts.FontStyle(bold ? 700 : 400, android.graphics.fonts.FontStyle.FONT_SLANT_UPRIGHT))
                    .build();
            } catch (Exception e) {
                // fall through to the plain font
            }
        }
        try {
            return Typeface.createFromAsset(am, "term-fonts/" + file);
        } catch (RuntimeException e) {
            return bold ? Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) : Typeface.MONOSPACE;
        }
    }

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    static TextView text(Context c, CharSequence s, float sp, int color, int weight) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(face(c, weight));
        t.setIncludeFontPadding(false);
        return t;
    }

    static TextView monoText(Context c, CharSequence s, float sp, int color) {
        TextView t = text(c, s, sp, color, REGULAR);
        t.setTypeface(mono(c));
        return t;
    }

    static TextView oneLine(TextView t, TextUtils.TruncateAt where) {
        t.setSingleLine(true);
        t.setEllipsize(where);
        return t;
    }

    static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    static GradientDrawable outline(int fill, int stroke, int strokePx, float radiusPx) {
        GradientDrawable g = round(fill, radiusPx);
        g.setStroke(strokePx, stroke);
        return g;
    }

    /** A background that ripples on touch. fill may be 0 for a transparent button. */
    static Drawable pressable(Theme t, Drawable fill, float radiusPx) {
        return new RippleDrawable(ColorStateList.valueOf(t.dark ? 0x26ffffff : 0x1a000000), fill, round(0xffffffff, radiusPx));
    }

    static ImageView icon(Context c, int res, int color, int sizeDp) {
        ImageView v = new ImageView(c);
        v.setImageResource(res);
        v.setImageTintList(ColorStateList.valueOf(color));
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return v;
    }

    /** A round icon-only button with a 44dp touch target. */
    static FrameLayout iconButton(Context c, Theme t, int res, int color, String label, View.OnClickListener l) {
        FrameLayout f = new FrameLayout(c);
        ImageView i = icon(c, res, color, 22);
        f.addView(i, new FrameLayout.LayoutParams(dp(c, 22), dp(c, 22), Gravity.CENTER));
        f.setBackground(pressable(t, null, dp(c, 22)));
        f.setContentDescription(label);
        f.setOnClickListener(l);
        f.setLayoutParams(new LinearLayout.LayoutParams(dp(c, 44), dp(c, 44)));
        return f;
    }

    static TextView primaryButton(Context c, Theme t, String label, View.OnClickListener l) {
        TextView b = text(c, label, 16, t.onAccent, SEMIBOLD);
        b.setGravity(Gravity.CENTER);
        b.setBackground(pressable(t, round(t.accent, dp(c, 16)), dp(c, 16)));
        b.setOnClickListener(l);
        return b;
    }

    static TextView secondaryButton(Context c, Theme t, String label, View.OnClickListener l) {
        TextView b = text(c, label, 14, t.text, MEDIUM);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(c, 12), 0, dp(c, 12), 0);
        b.setBackground(pressable(t, outline(t.surface, t.line, dp(c, 1), dp(c, 14)), dp(c, 14)));
        b.setOnClickListener(l);
        return b;
    }

    static View dot(Context c, int color, int sizeDp) {
        View d = new View(c);
        d.setBackground(round(color, dp(c, sizeDp)));
        d.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return d;
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        // Baseline alignment shifts children with text inside them and clips their bottom edge
        l.setBaselineAligned(false);
        return l;
    }

    static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    static LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
    }

    /** A dialog that slides up from the bottom, with a grabber, rounded top and the theme's sheet color. */
    static Dialog bottomSheet(Context c, Theme t, View content) {
        Dialog d = new Dialog(c);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = column(c);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(t.sheet);
        float r = dp(c, 24);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        box.setBackground(bg);
        box.setPadding(dp(c, 20), dp(c, 10), dp(c, 20), dp(c, 20));
        View grab = new View(c);
        grab.setBackground(round(t.line, dp(c, 2)));
        LinearLayout.LayoutParams glp = lp(dp(c, 36), dp(c, 4));
        glp.gravity = Gravity.CENTER_HORIZONTAL;
        glp.bottomMargin = dp(c, 12);
        box.addView(grab, glp);
        box.addView(content);
        d.setContentView(box);
        Window w = d.getWindow();
        w.setBackgroundDrawable(new ColorDrawable(0));
        w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        w.setGravity(Gravity.BOTTOM);
        w.setDimAmount(t.dark ? 0.62f : 0.4f);
        w.setWindowAnimations(android.R.style.Animation_InputMethod);
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        return d;
    }
}
