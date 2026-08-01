package dev.gabea.mcstocks.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public final class Text {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final String COLOR_CODES = "0123456789AaBbCcDdEeFfKkLlMmNnOoRr";

    private Text() {
    }

    public static String color(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        char[] chars = input.toCharArray();
        for (int index = 0; index < chars.length - 1; index++) {
            if (chars[index] == '&' && COLOR_CODES.indexOf(chars[index + 1]) >= 0) {
                chars[index] = '\u00A7';
                chars[index + 1] = Character.toLowerCase(chars[index + 1]);
            }
        }
        return new String(chars);
    }

    public static Component component(String input) {
        return LEGACY.deserialize(input == null ? "" : input);
    }
}
