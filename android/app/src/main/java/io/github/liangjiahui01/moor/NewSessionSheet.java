package io.github.liangjiahui01.moor;

import android.app.Dialog;
import android.content.Context;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/** Bottom sheet for a new session: a name first, then what to run and where. */
final class NewSessionSheet {

    interface Done {
        void create(String name, String command, String dir);
    }

    private static final String[] COMMANDS = {"", "claude", "herdr", null};

    private final Context c;
    private final Theme t;
    private final List<String> taken;
    private final List<String> dirs;
    private final Done done;
    private int choice;
    private final View[] options = new View[4];
    private EditText custom;

    NewSessionSheet(Context c, Theme t, List<String> taken, List<String> dirs, Done done) {
        this.c = c;
        this.t = t;
        this.taken = taken;
        this.dirs = dirs;
        this.done = done;
    }

    static EditText field(Context c, Theme t, String value, String hint) {
        EditText e = new EditText(c);
        e.setText(value);
        if (hint != null) e.setHint(hint);
        e.setSingleLine(true);
        e.setTextColor(t.text);
        e.setHintTextColor(t.faint);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        e.setTypeface(Ui.face(c, Ui.REGULAR));
        e.setPadding(Ui.dp(c, 14), 0, Ui.dp(c, 14), 0);
        e.setBackground(Ui.outline(t.bg, t.line, Ui.dp(c, 1), Ui.dp(c, 14)));
        e.setOnFocusChangeListener((v, has) -> v.setBackground(Ui.outline(t.bg, has ? t.accent : t.line,
            Ui.dp(c, has ? 1.5f : 1), Ui.dp(c, 14))));
        return e;
    }

    void show() {
        LinearLayout box = Ui.column(c);
        LinearLayout head = Ui.row(c);
        head.addView(Ui.text(c, c.getString(R.string.new_session), 21, t.text, Ui.SEMIBOLD), Ui.weight(1));
        head.addView(Ui.text(c, c.getString(R.string.new_session_hint), 12, t.faint, Ui.REGULAR));
        box.addView(head);

        box.addView(label(R.string.field_name));
        EditText name = field(c, t, "", c.getString(R.string.field_name_hint));
        name.setImeOptions(EditorInfo.IME_ACTION_DONE);
        box.addView(name, new LinearLayout.LayoutParams(-1, dp(50)));

        box.addView(label(R.string.field_run));
        int[][] spec = {
            {R.drawable.ic_terminal, R.string.run_shell, R.string.run_shell_sub},
            {R.drawable.ic_sparkle, R.string.run_claude, 0},
            {R.drawable.ic_grid, R.string.run_herdr, R.string.run_herdr_sub},
            {R.drawable.ic_plus, R.string.run_custom, R.string.run_custom_sub},
        };
        for (int row = 0; row < 2; row++) {
            LinearLayout r = Ui.row(c);
            for (int col = 0; col < 2; col++) {
                int k = row * 2 + col;
                View o = option(k, spec[k]);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(64), 1f);
                lp.setMargins(col == 0 ? 0 : dp(4), 0, col == 0 ? dp(4) : 0, 0);
                r.addView(o, lp);
                options[k] = o;
            }
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, dp(64));
            rlp.topMargin = row == 0 ? 0 : dp(8);
            box.addView(r, rlp);
        }
        custom = field(c, t, "", c.getString(R.string.field_command_hint));
        custom.setTypeface(Ui.mono(c));
        custom.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, dp(48));
        clp.topMargin = dp(8);
        box.addView(custom, clp);

        box.addView(label(R.string.field_dir));
        EditText dir = field(c, t, dirs.isEmpty() ? "~" : dirs.get(0), "~");
        dir.setTypeface(Ui.mono(c));
        dir.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        box.addView(dir, new LinearLayout.LayoutParams(-1, dp(48)));
        if (!dirs.isEmpty()) {
            HorizontalScrollView hs = new HorizontalScrollView(c);
            hs.setHorizontalScrollBarEnabled(false);
            LinearLayout chips = Ui.row(c);
            chips.setPadding(0, dp(8), 0, 0);
            for (String d : dirs) {
                TextView chip = Ui.monoText(c, d, 11.5f, t.sub);
                chip.setGravity(Gravity.CENTER);
                chip.setPadding(dp(10), 0, dp(10), 0);
                chip.setBackground(Ui.pressable(t, Ui.outline(t.surface, t.line, dp(1), dp(15)), dp(15)));
                chip.setOnClickListener(v -> {
                    dir.setText(d);
                    dir.setSelection(d.length());
                });
                LinearLayout.LayoutParams lp = Ui.lp(-2, dp(30));
                lp.rightMargin = dp(6);
                chips.addView(chip, lp);
            }
            hs.addView(chips);
            box.addView(hs);
        }

        Dialog d = Ui.bottomSheet(c, t, box);
        Runnable submit = () -> {
            String n = Sessions.cleanName(name.getText().toString());
            if (n.isEmpty()) n = autoName();
            if (taken.contains(n)) {
                name.setError(c.getString(R.string.err_name_taken));
                return;
            }
            String cmd = COMMANDS[choice] != null ? COMMANDS[choice] : custom.getText().toString().trim();
            Prefs.put(c, "new_cmd", COMMANDS[choice] != null ? COMMANDS[choice] : "custom:" + cmd);
            d.dismiss();
            done.create(n, cmd, dir.getText().toString().trim());
        };
        name.setOnEditorActionListener((v, a, e) -> {
            submit.run();
            return true;
        });
        TextView create = Ui.primaryButton(c, t, c.getString(R.string.create_open), v -> submit.run());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, dp(54));
        blp.topMargin = dp(20);
        box.addView(create, blp);

        // Last choice, else the default command from settings
        String saved = Prefs.raw(c).getString("new_cmd", Prefs.load(c).startup);
        choice = 0;
        if (saved.startsWith("custom:")) {
            choice = 3;
            custom.setText(saved.substring(7));
        } else {
            for (int k = 0; k < 3; k++) if (COMMANDS[k].equals(saved)) choice = k;
            if (choice == 0 && !saved.isEmpty()) {
                choice = 3;
                custom.setText(saved);
            }
        }
        paint();
        d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
            | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        d.show();
        name.requestFocus();
    }

    private View option(int k, int[] spec) {
        LinearLayout o = Ui.row(c);
        o.setPadding(dp(12), 0, dp(8), 0);
        LinearLayout iconBox = new LinearLayout(c);
        iconBox.setGravity(Gravity.CENTER);
        iconBox.addView(Ui.icon(c, spec[0], t.text, 18));
        o.addView(iconBox, Ui.lp(dp(34), dp(34)));
        LinearLayout labels = Ui.column(c);
        labels.setPadding(dp(10), 0, 0, 0);
        labels.addView(Ui.oneLine(Ui.text(c, c.getString(spec[1]), 14.5f, t.text, Ui.SEMIBOLD), TextUtils.TruncateAt.END));
        TextView sub = spec[2] != 0 ? Ui.text(c, c.getString(spec[2]), 11.5f, t.faint, Ui.REGULAR)
            : Ui.monoText(c, "claude", 11, t.faint);
        sub.setPadding(0, dp(3), 0, 0);
        labels.addView(Ui.oneLine(sub, TextUtils.TruncateAt.END));
        o.addView(labels, Ui.weight(1));
        o.setOnClickListener(v -> {
            choice = k;
            paint();
            if (k == 3) custom.requestFocus();
        });
        return o;
    }

    private void paint() {
        for (int k = 0; k < 4; k++) {
            View o = options[k];
            boolean on = k == choice;
            o.setBackground(Ui.pressable(t, Ui.outline(on ? t.accentSoft : t.surface, on ? t.accent : t.line,
                dp(on ? 1.5f : 1), dp(14)), dp(14)));
            LinearLayout iconBox = (LinearLayout) ((LinearLayout) o).getChildAt(0);
            iconBox.setBackground(Ui.round(on ? t.accent : t.chip, dp(10)));
            ((android.widget.ImageView) iconBox.getChildAt(0)).setImageTintList(
                android.content.res.ColorStateList.valueOf(on ? t.onAccent : t.text));
        }
        custom.setVisibility(choice == 3 ? View.VISIBLE : View.GONE);
    }

    private String autoName() {
        for (int n = 1; ; n++) {
            String s = c.getString(R.string.auto_name, n);
            if (!taken.contains(s)) return s;
        }
    }

    private TextView label(int res) {
        TextView l = Ui.text(c, c.getString(res), 12.5f, t.sub, Ui.MEDIUM);
        l.setPadding(dp(2), dp(18), 0, dp(8));
        return l;
    }

    private int dp(float v) {
        return Ui.dp(c, v);
    }
}
