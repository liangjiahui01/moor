package io.github.liangjiahui01.moor;

import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.WcWidth;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What a tap on the terminal points at: a web link, or text that may name a file on the machine.
 * Programs wrap long paths onto the next line, so a token that touches a line's edge is also tried joined
 * with the text across that edge; the machine keeps the first that exists.
 */
final class PathTap {
    private PathTap() {}

    static final class Hit {
        /** A web link to open in the browser, or null. */
        String url;
        /** Texts that may name a file, best guess first. */
        final List<String> cands = new ArrayList<>();
        /** The tapped token, for messages. */
        String label;
    }

    private static final String PATH_CHARS = "._-/~+@:#%";
    private static final String URL_CHARS = "._-/~+@:#%?&=!$*,;'()[]";

    static Hit at(TerminalBuffer b, int col, int row, int cols, int rows) {
        if (col < 0 || col >= cols || row >= rows || row < -b.getActiveTranscriptRows()) return null;
        Hit h = new Hit();
        Span url = token(b, col, row, cols, true);
        if (url != null) {
            String u = url.text;
            int i = u.indexOf("https://");
            if (i < 0) i = u.indexOf("http://");
            if (i >= 0) {
                // A link wraps only when it fills its line to the edge
                String full = (url.startsAtEdge ? prevTail(b, row, cols, true, true) : "") + url.text
                    + (url.endsAtEdge ? nextHead(b, row, cols, rows, true, true) : "");
                int j = full.indexOf("https://");
                if (j < 0) j = full.indexOf("http://");
                h.url = trimEnd(full.substring(Math.max(j, 0)), ".,;:!?'\")]>");
                h.label = h.url;
                return h;
            }
        }
        Span p = token(b, col, row, cols, false);
        if (p == null) return null;
        String prev = p.startsAtEdgeLoose ? prevTail(b, row, cols, false, false) : "";
        String next = p.endsAtEdgeLoose ? nextHead(b, row, cols, rows, false, false) : "";
        Set<String> out = new LinkedHashSet<>();
        for (String s : new String[]{prev + p.text + next, p.text + next, prev + p.text, p.text}) {
            String c = clean(s);
            if (!c.isEmpty()) out.add(c);
        }
        // Words in Chinese stuck to a path: "改了site/a.md" → also try "site/a.md". Only the tapped token is cut,
        // not its joins with the lines around, which would pick a path from a neighbouring line.
        String s0 = clean(p.text);
        for (int i = 1; i < s0.length(); i++) {
            char a = s0.charAt(i - 1), c = s0.charAt(i);
            if (a > 0x7f && c <= 0x7f) out.add(clean(s0.substring(i)));
            if (a <= 0x7f && c > 0x7f) out.add(clean(s0.substring(0, i)));
        }
        for (String s : out) if (looksLikePath(s)) h.cands.add(s);
        if (h.cands.isEmpty()) return null;
        h.label = clean(p.text);
        return h;
    }

    private static final class Span {
        String text;
        /** Starts at the line's left edge (after a border), and the line above is full to its edge. */
        boolean startsAtEdge, endsAtEdge;
        /** Only blanks or a border between the token and the edge. */
        boolean startsAtEdgeLoose, endsAtEdgeLoose;
    }

    private static Span token(TerminalBuffer b, int col, int row, int cols, boolean url) {
        String right = b.getSelectedText(col, row, cols - 1, row, false);
        if (right.isEmpty() || !part(right.codePointAt(0), url)) return null;
        String left = col == 0 ? "" : b.getSelectedText(0, row, col, row, false);
        String first = new String(Character.toChars(right.codePointAt(0)));
        if (left.endsWith(first)) left = left.substring(0, left.length() - first.length());
        int s = left.length();
        while (s > 0) {
            int cp = left.codePointBefore(s);
            if (!part(cp, url)) break;
            s -= Character.charCount(cp);
        }
        int e = 0;
        while (e < right.length()) {
            int cp = right.codePointAt(e);
            if (!part(cp, url)) break;
            e += Character.charCount(cp);
        }
        Span sp = new Span();
        sp.text = left.substring(s) + right.substring(0, e);
        String before = left.substring(0, s), after = right.substring(e);
        sp.startsAtEdgeLoose = blankOrBorder(before);
        sp.endsAtEdgeLoose = blankOrBorder(after);
        sp.startsAtEdge = before.isEmpty() && rowFull(b, row - 1, cols);
        sp.endsAtEdge = after.isEmpty() && col + width(right.substring(0, e)) >= cols;
        return sp;
    }

    /** The last token of the line above (for a path the program wrapped). */
    private static String prevTail(TerminalBuffer b, int row, int cols, boolean url, boolean strict) {
        if (row - 1 < -b.getActiveTranscriptRows()) return "";
        if (strict && !rowFull(b, row - 1, cols)) return "";
        String s = stripBorders(b.getSelectedText(0, row - 1, cols - 1, row - 1, false), false);
        int i = s.length();
        while (i > 0) {
            int cp = s.codePointBefore(i);
            if (!part(cp, url)) break;
            i -= Character.charCount(cp);
        }
        return s.substring(i);
    }

    /** The first token of the line below. */
    private static String nextHead(TerminalBuffer b, int row, int cols, int rows, boolean url, boolean strict) {
        if (row + 1 >= rows) return "";
        String s = b.getSelectedText(0, row + 1, cols - 1, row + 1, false);
        s = strict ? s : stripBorders(s, true);
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            if (!part(cp, url)) break;
            i += Character.charCount(cp);
        }
        return s.substring(0, i);
    }

    private static boolean rowFull(TerminalBuffer b, int row, int cols) {
        if (row < -b.getActiveTranscriptRows()) return false;
        String last = b.getSelectedText(cols - 1, row, cols - 1, row, false);
        return !last.trim().isEmpty() && !border(last.codePointAt(0));
    }

    private static String stripBorders(String s, boolean leading) {
        if (leading) {
            int i = 0;
            while (i < s.length() && (s.charAt(i) == ' ' || border(s.charAt(i)))) i++;
            return s.substring(i);
        }
        int i = s.length();
        while (i > 0 && (s.charAt(i - 1) == ' ' || border(s.charAt(i - 1)))) i--;
        return s.substring(0, i);
    }

    private static boolean blankOrBorder(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != ' ' && !border(c)) return false;
        }
        return true;
    }

    /** Box drawing and block characters: pane borders. */
    private static boolean border(int cp) {
        return cp >= 0x2500 && cp <= 0x259f;
    }

    private static boolean part(int cp, boolean url) {
        if (cp < 0x80) return Character.isLetterOrDigit(cp) || (url ? URL_CHARS : PATH_CHARS).indexOf(cp) >= 0;
        // Letters of any script (folder names in Chinese), not punctuation or symbols
        return !url && Character.isLetterOrDigit(cp);
    }

    private static int width(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            w += Math.max(0, WcWidth.width(cp));
            i += Character.charCount(cp);
        }
        return w;
    }

    private static String clean(String s) {
        s = s.trim();
        if (s.startsWith("file://")) s = s.substring(7);
        while (!s.isEmpty() && "@:".indexOf(s.charAt(0)) >= 0) s = s.substring(1);
        return trimEnd(s, ".,;:!?#");
    }

    private static String trimEnd(String s, String chars) {
        int e = s.length();
        while (e > 0 && chars.indexOf(s.charAt(e - 1)) >= 0) e--;
        return s.substring(0, e);
    }

    /** Has a slash, starts at home, or ends in a file extension: worth asking the machine about. */
    static boolean looksLikePath(String s) {
        if (s.length() < 3 || s.matches("[\\d.:/-]+")) return false;
        if (s.startsWith("~/") || s.startsWith("/") || s.startsWith("./") || s.startsWith("../")) return true;
        String bare = s.replaceAll("(:\\d+)+$|#L\\d+.*$", "");
        if (bare.indexOf('/') > 0 && bare.matches(".*[\\p{L}\\d].*")) return true;
        return bare.matches("[^/]*[\\p{L}\\d_-][^/]*\\.[A-Za-z][A-Za-z0-9]{0,9}");
    }
}
