package com.ziptool;

import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * Internationalization support.
 *
 * Three language modes are available:
 *   AUTO - resolve from the system default locale (Chinese -> Chinese, else English)
 *   EN   - force English
 *   ZH   - force Chinese
 *
 * Messages are loaded from messages_English.properties / messages_Chinese.properties
 * bundled on the classpath. Every user-visible string goes through tr() so the
 * whole UI (including the running GUI) can be switched at runtime.
 */
public enum Lang {

    /** Follow the system default locale. */
    AUTO,
    /** Force English. */
    EN,
    /** Force Chinese. */
    ZH;

    /** Resource bundle base name (classpath root). */
    private static final String BASE = "messages";

    /**
     * Resolve a possibly-AUTO mode into a concrete language.
     *
     * @param mode the requested mode
     * @return EN or ZH
     */
    public static Lang resolve(Lang mode) {
        if (mode == AUTO) {
            String lang = Locale.getDefault().getLanguage();
            return (lang != null && lang.toLowerCase(Locale.ROOT).startsWith("zh")) ? ZH : EN;
        }
        return mode;
    }

    /**
     * Translate a message key into the language of the given mode.
     * Falls back to returning the key itself if the key is missing.
     *
     * @param mode the language mode
     * @param key  the message key
     * @return the localized string
     */
    public static String tr(Lang mode, String key) {
        Lang resolved = resolve(mode);
        String lang = (resolved == ZH) ? "Chinese" : "English";
        try {
            ResourceBundle bundle = ResourceBundle.getBundle(BASE + "_" + lang);
            return bundle.getString(key);
        } catch (MissingResourceException e) {
            return key;
        }
    }

    /**
     * Translate a message key and apply format arguments.
     * Falls back to the raw key on any formatting problem.
     *
     * @param mode the language mode
     * @param key  the message key
     * @param args format arguments for String.format
     * @return the localized, formatted string
     */
    public static String tr(Lang mode, String key, Object... args) {
        String s = tr(mode, key);
        try {
            return String.format(s, args);
        } catch (RuntimeException e) {
            return s;
        }
    }
}
