package io.github.liangjiahui01.moor;

import android.content.Context;
import android.content.res.Configuration;

import com.termux.terminal.TerminalColors;

import java.util.Properties;

/** Two palettes: Graphite (dark) and Paper (light). "system" follows the phone's dark mode. */
final class Theme {
    final boolean dark;
    final int bg, surface, well, line, divider, text, sub, faint, accent, onAccent, accentSoft,
        amber, amberBg, amberLine, red, key, keyBar, chip, sheet, scrim;

    private Theme(boolean dark) {
        this.dark = dark;
        if (dark) {
            bg = 0xff0b0d10; surface = 0xff14171c; well = 0xff0e1014; line = 0xff232830; divider = 0xff1d2026;
            text = 0xffe8eaed; sub = 0xffa9b0ba; faint = 0xff8a929e;
            accent = 0xff3ddc97; onAccent = 0xff06110b; accentSoft = 0xff12201a;
            amber = 0xfff2b84b; amberBg = 0xff17150f; amberLine = 0xff4a3d1e; red = 0xfff07178;
            key = 0xff1a1d23; keyBar = 0xff111317; chip = 0xff23262d; sheet = 0xff111317; scrim = 0x9e050608;
        } else {
            bg = 0xfff6f6f4; surface = 0xffffffff; well = 0xfff0f1f3; line = 0xffe2e4e8; divider = 0xffeceef1;
            text = 0xff14171c; sub = 0xff4a515c; faint = 0xff646b76;
            accent = 0xff0b8a5c; onAccent = 0xffffffff; accentSoft = 0xffe7f6ef;
            amber = 0xff9a6200; amberBg = 0xfffff8ea; amberLine = 0xfff0d9a8; red = 0xffc93c3c;
            key = 0xffffffff; keyBar = 0xffeceef1; chip = 0xffeceef1; sheet = 0xfff6f6f4; scrim = 0x66000000;
        }
    }

    private static Theme cached;
    private static String cachedKey;

    static Theme get(Context c) {
        String mode = Prefs.load(c).theme;
        boolean night = (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
            == Configuration.UI_MODE_NIGHT_YES;
        boolean dark = mode.equals("dark") || (mode.equals("system") && night);
        String key = dark ? "d" : "l";
        if (cached == null || !key.equals(cachedKey)) {
            cached = new Theme(dark);
            cachedKey = key;
        }
        return cached;
    }

    /** Terminal colors follow the theme. Callers reset the emulator's colors afterwards. */
    void applyTerminalColors() {
        Properties p = new Properties();
        String[] c = dark
            ? new String[]{"#1a1d23", "#f07178", "#3ddc97", "#f2b84b", "#7aa2f7", "#c3a6ff", "#5ccfe6", "#d5d9df",
                "#4a515c", "#ff8f98", "#6be8b1", "#ffd27a", "#9ab8ff", "#d6c2ff", "#8be3f5", "#f2f4f7"}
            : new String[]{"#24292f", "#cf222e", "#116329", "#8a5a00", "#0550ae", "#8250df", "#1b7c83", "#6e7781",
                "#57606a", "#a40e26", "#1a7f37", "#633c01", "#218bff", "#a475f9", "#3192aa", "#8c959f"};
        for (int i = 0; i < c.length; i++) p.put("color" + i, c[i]);
        p.put("foreground", dark ? "#d5d9df" : "#1f2328");
        p.put("background", dark ? "#0b0d10" : "#fcfcfb");
        p.put("cursor", dark ? "#e8eaed" : "#1f2328");
        TerminalColors.COLOR_SCHEME.updateWith(p);
    }

    int terminalBg() { return dark ? 0xff0b0d10 : 0xfffcfcfb; }
}
