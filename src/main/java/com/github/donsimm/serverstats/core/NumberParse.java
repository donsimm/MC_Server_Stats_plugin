package com.github.donsimm.serverstats.core;

/** Liest Zahlen aus Platzhalter-Texten wie "1,234", "1'234.5", "1.234,5" oder "42". */
public final class NumberParse {
    private NumberParse() {
    }

    /** @return die Zahl oder NaN, wenn der Text keine Zahl enthaelt */
    public static double parse(String text) {
        if (text == null) {
            return Double.NaN;
        }
        StringBuilder cleaned = new StringBuilder();
        for (char c : text.toCharArray()) {
            if ((c >= '0' && c <= '9') || c == '.' || c == ',' || c == '-') {
                cleaned.append(c);
            }
        }
        String s = cleaned.toString();
        if (s.isEmpty() || s.equals("-")) {
            return Double.NaN;
        }
        int lastDot = s.lastIndexOf('.');
        int lastComma = s.lastIndexOf(',');
        if (lastDot >= 0 && lastComma >= 0) {
            // Das spaetere Zeichen ist das Dezimalzeichen, das andere Tausendertrenner.
            char decimal = lastDot > lastComma ? '.' : ',';
            char thousands = decimal == '.' ? ',' : '.';
            s = s.replace(String.valueOf(thousands), "").replace(decimal, '.');
        } else if (lastComma >= 0) {
            boolean thousands = s.indexOf(',') != lastComma || s.length() - lastComma - 1 == 3;
            s = thousands ? s.replace(",", "") : s.replace(',', '.');
        } else if (lastDot >= 0 && s.indexOf('.') != lastDot) {
            s = s.replace(".", "");
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
