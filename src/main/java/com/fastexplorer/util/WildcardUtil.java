package com.fastexplorer.util;

import java.util.regex.Pattern;

public final class WildcardUtil {

    private WildcardUtil() {}

    /** ファイル名ワイルドカード (*, ?) を正規表現に変換 */
    public static Pattern globToPattern(String glob) {
        if (glob == null || glob.isBlank()) {
            return null;
        }
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append(".");
                case '.', '(', ')', '+', '|', '^', '$', '{', '}', '[', ']', '\\' ->
                        regex.append('\\').append(c);
                default -> regex.append(c);
            }
        }
        regex.append("$");
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    public static boolean matches(String text, Pattern globPattern) {
        return globPattern == null || globPattern.matcher(text).matches();
    }
}
