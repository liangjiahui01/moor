package io.github.liangjiahui01.moor;

import android.app.Dialog;
import android.content.Context;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Saved commands and prompts sent to the terminal with one tap ("continue", "run the tests", "git status").
 * One list for all machines, kept as JSON in the "quick_cmds" preference (copied by "Set up another device").
 */
final class QuickCommands {
    static final String PREF = "quick_cmds";

    static final class Item {
        String label, text;
        boolean enter;

        Item(String label, String text, boolean enter) {
            this.label = label;
            this.text = text;
            this.enter = enter;
        }

        /** The chip's text: the name, else the first line of the command. */
        String title() {
            if (!label.isEmpty()) return label;
            String first = text.split("\n", 2)[0].trim();
            return first.length() > 24 ? first.substring(0, 24) + "…" : first;
        }
    }

    interface Sender {
        void send(String text, boolean enter);
    }

    static List<Item> load(Context c) {
        List<Item> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(Prefs.raw(c).getString(PREF, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                String text = o.optString("text");
                if (!text.isEmpty()) out.add(new Item(o.optString("label"), text, o.optBoolean("enter", true)));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    static void save(Context c, List<Item> items) {
        JSONArray a = new JSONArray();
        try {
            for (Item i : items) a.put(new JSONObject().put("label", i.label).put("text", i.text).put("enter", i.enter));
        } catch (Exception ignored) {
        }
        Prefs.put(c, PREF, a.toString());
    }

    private final Context c;
    private final Theme t;
    private final Sender sender;
    /** Runs after a command is sent (e.g. close the panel it was in). */
    private final Runnable afterSend;
    private final LinearLayout chips;
    private final HorizontalScrollView view;

    QuickCommands(Context c, Theme t, Sender sender, Runnable afterSend) {
        this.c = c;
        this.t = t;
        this.sender = sender;
        this.afterSend = afterSend;
        view = new HorizontalScrollView(c);
        view.setHorizontalScrollBarEnabled(false);
        chips = Ui.row(c);
        view.addView(chips);
        render();
    }

    /** The row of chips: tap sends, long press edits, the last one adds. */
    View view() {
        return view;
    }

    void render() {
        chips.removeAllViews();
        List<Item> items = load(c);
        for (int i = 0; i < items.size(); i++) {
            final int k = i;
            Item it = items.get(i);
            TextView chip = chip(it.title(), t.text);
            chip.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                sender.send(it.text, it.enter);
                if (afterSend != null) afterSend.run();
            });
            chip.setOnLongClickListener(v -> {
                edit(k);
                return true;
            });
            chips.addView(chip, chipLp());
        }
        TextView add = chip(c.getString(items.isEmpty() ? R.string.quick_add_first : R.string.quick_add), t.accent);
        add.setOnClickListener(v -> edit(-1));
        chips.addView(add, chipLp());
    }

    private TextView chip(String s, int color) {
        TextView chip = Ui.text(c, s, 13, color, Ui.MEDIUM);
        Ui.oneLine(chip, TextUtils.TruncateAt.END);
        chip.setMaxWidth(Ui.dp(c, 220));
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(Ui.dp(c, 14), 0, Ui.dp(c, 14), 0);
        chip.setBackground(Ui.pressable(t, t.dark ? Ui.round(t.key, Ui.dp(c, 12)) : Ui.outline(t.key, t.line, Ui.dp(c, 1), Ui.dp(c, 12)), Ui.dp(c, 12)));
        return chip;
    }

    private LinearLayout.LayoutParams chipLp() {
        LinearLayout.LayoutParams lp = Ui.lp(-2, Ui.dp(c, 38));
        lp.setMargins(Ui.dp(c, 2.5f), Ui.dp(c, 2.5f), Ui.dp(c, 2.5f), Ui.dp(c, 2.5f));
        return lp;
    }

    /** Add (index -1) or edit a command; editing also offers delete. */
    private void edit(int index) {
        List<Item> items = load(c);
        Item cur = index >= 0 ? items.get(index) : new Item("", "", true);
        LinearLayout box = Ui.column(c);
        TextView title = Ui.text(c, c.getString(index >= 0 ? R.string.quick_edit_title : R.string.quick_add_title), 20, t.text, Ui.SEMIBOLD);
        title.setPadding(0, 0, 0, Ui.dp(c, 14));
        box.addView(title);

        box.addView(label(R.string.quick_text));
        EditText text = NewSessionSheet.field(c, t, cur.text, c.getString(R.string.quick_text_hint));
        text.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        text.setMinLines(2);
        text.setMaxLines(6);
        text.setGravity(Gravity.TOP | Gravity.START);
        text.setPadding(Ui.dp(c, 14), Ui.dp(c, 12), Ui.dp(c, 14), Ui.dp(c, 12));
        box.addView(text, new LinearLayout.LayoutParams(-1, -2));

        box.addView(label(R.string.quick_label));
        EditText name = NewSessionSheet.field(c, t, cur.label, c.getString(R.string.quick_label_hint));
        box.addView(name, new LinearLayout.LayoutParams(-1, Ui.dp(c, 48)));

        final boolean[] enter = {cur.enter};
        box.addView(toggle(R.string.quick_enter, enter));

        Dialog d = Ui.bottomSheet(c, t, box);
        TextView save = Ui.primaryButton(c, t, c.getString(R.string.quick_save), v -> {
            String body = text.getText().toString();
            while (body.endsWith("\n")) body = body.substring(0, body.length() - 1);
            if (body.trim().isEmpty()) {
                text.setError(c.getString(R.string.quick_text_empty));
                return;
            }
            Item it = new Item(name.getText().toString().trim(), body, enter[0]);
            List<Item> now = load(c);
            if (index >= 0 && index < now.size()) now.set(index, it);
            else now.add(it);
            save(c, now);
            d.dismiss();
            render();
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, Ui.dp(c, 52));
        blp.topMargin = Ui.dp(c, 18);
        box.addView(save, blp);
        if (index >= 0) {
            TextView del = Ui.text(c, c.getString(R.string.quick_delete), 15, t.red, Ui.MEDIUM);
            del.setGravity(Gravity.CENTER);
            del.setBackground(Ui.pressable(t, null, Ui.dp(c, 12)));
            del.setOnClickListener(v -> {
                List<Item> now = load(c);
                if (index < now.size()) now.remove(index);
                save(c, now);
                d.dismiss();
                render();
            });
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, Ui.dp(c, 48));
            dlp.topMargin = Ui.dp(c, 6);
            box.addView(del, dlp);
        }
        d.show();
        if (index < 0) text.requestFocus();
    }

    private TextView label(int res) {
        TextView l = Ui.text(c, c.getString(res), 12.5f, t.sub, Ui.MEDIUM);
        l.setPadding(Ui.dp(c, 2), Ui.dp(c, 14), 0, Ui.dp(c, 7));
        return l;
    }

    private View toggle(int res, boolean[] on) {
        LinearLayout r = Ui.row(c);
        r.setPadding(Ui.dp(c, 2), Ui.dp(c, 14), 0, 0);
        r.addView(Ui.text(c, c.getString(res), 14.5f, t.text, Ui.REGULAR), Ui.weight(1));
        FrameLayout sw = new FrameLayout(c);
        View knob = new View(c);
        sw.addView(knob, new FrameLayout.LayoutParams(Ui.dp(c, 22), Ui.dp(c, 22), Gravity.CENTER_VERTICAL));
        Runnable paint = () -> {
            sw.setBackground(Ui.round(on[0] ? t.accent : (t.dark ? 0xff45475a : 0xffccd0da), Ui.dp(c, 14)));
            knob.setBackground(Ui.round(on[0] ? t.onAccent : (t.dark ? t.faint : 0xffffffff), Ui.dp(c, 11)));
            ((FrameLayout.LayoutParams) knob.getLayoutParams()).gravity = Gravity.CENTER_VERTICAL | (on[0] ? Gravity.END : Gravity.START);
            knob.requestLayout();
        };
        sw.setPadding(Ui.dp(c, 3), 0, Ui.dp(c, 3), 0);
        paint.run();
        r.addView(sw, Ui.lp(Ui.dp(c, 46), Ui.dp(c, 28)));
        r.setOnClickListener(v -> {
            on[0] = !on[0];
            paint.run();
        });
        return r;
    }
}
