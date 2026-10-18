package io.github.liangjiahui01.moor;

import android.content.Context;
import android.content.res.Configuration;

import com.termux.terminal.TerminalColors;

import java.util.Properties;

/**
 * Two palettes from Catppuccin, matching Ghostty's themes: Mocha (dark) and Latte (light).
 * "system" follows the phone's dark mode.
 */
final class Theme {
    final boolean dark;
    final int bg, surface, well, line, divider, text, sub, faint, accent, onAccent, accentSoft, ok,
        amber, amberBg, amberLine, red, key, keyBar, chip, sheet, scrim;

    private Theme(boolean dark) {
        this.dark = dark;
        if (dark) {
            bg = 0xff1e1e2e; surface = 0xff27283a; well = 0xff181825; line = 0xff313244; divider = 0xff2a2b3c;
            text = 0xffcdd6f4; sub = 0xffa6adc8; faint = 0xff9399b2;
            accent = 0xffcba6f7; onAccent = 0xff11111b; accentSoft = 0xff2e2a42; ok = 0xffa6e3a1;
            amber = 0xfff9e2af; amberBg = 0xff29283a; amberLine = 0xff5b5446; red = 0xfff38ba8;
            key = 0xff2a2b3c; keyBar = 0xff181825; chip = 0xff313244; sheet = 0xff181825; scrim = 0x9e11111b;
        } else {
            bg = 0xffeff1f5; surface = 0xffffffff; well = 0xffe6e9ef; line = 0xffccd0da; divider = 0xffdce0e8;
            text = 0xff4c4f69; sub = 0xff5c5f77; faint = 0xff6c6f85;
            accent = 0xff8839ef; onAccent = 0xffffffff; accentSoft = 0xffefe6fd; ok = 0xff40a02b;
            amber = 0xffb86b0a; amberBg = 0xfffdf3e3; amberLine = 0xfff0d4a2; red = 0xffd20f39;
            key = 0xffffffff; keyBar = 0xffe6e9ef; chip = 0xffdce0e8; sheet = 0xffeff1f5; scrim = 0x66000000;
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

    /** Terminal colors follow the theme: Ghostty's Catppuccin Mocha and Latte. Callers reset the emulator's colors afterwards. */
    void applyTerminalColors() {
        Properties p = new Properties();
        String[] c = dark
            ? new String[]{"#45475a", "#f38ba8", "#a6e3a1", "#f9e2af", "#89b4fa", "#f5c2e7", "#94e2d5", "#a6adc8",
                "#585b70", "#f37799", "#89d88b", "#ebd391", "#74a8fc", "#f2aede", "#6bd7ca", "#bac2de"}
            : new String[]{"#5c5f77", "#d20f39", "#40a02b", "#df8e1d", "#1e66f5", "#ea76cb", "#179299", "#acb0be",
                "#6c6f85", "#de293e", "#49af3d", "#eea02d", "#456eff", "#fe85d8", "#2d9fa8", "#bcc0cc"};
        for (int i = 0; i < c.length; i++) p.put("color" + i, c[i]);
        p.put("foreground", dark ? "#cdd6f4" : "#4c4f69");
        p.put("background", dark ? "#1e1e2e" : "#eff1f5");
        p.put("cursor", dark ? "#f5e0dc" : "#dc8a78");
        TerminalColors.COLOR_SCHEME.updateWith(p);
    }

    int terminalBg() { return bg; }
}
