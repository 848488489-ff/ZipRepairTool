package com.github._848488489_ff.mcpack_deobfuscator;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Optional check-configuration file loader + validator.
 *
 * <p>Configuration is <b>never required</b>: with no file, {@link CheckOptions#defaults()}
 * is used. When a file is given it is a simple UTF-8 {@code key=value} properties
 * file ({@code #} or {@code !} comments, blank lines ignored). Unknown keys,
 * malformed lines, duplicate keys, invalid booleans, missing file, a UTF-8 BOM,
 * malformed UTF-8, or the U+FFFD replacement character are rejected with a
 * clear message. The file can be checked on its own (see {@link #validate}).
 */
public final class CheckConfig {

    private CheckConfig() {
    }

    /** Thrown when a configuration file is missing, malformed, or has unknown/invalid content. */
    public static final class ConfigException extends Exception {
        private static final long serialVersionUID = 1L;
        public ConfigException(String message) {
            super(message);
        }
    }

    /** All recognized keys. */
    public static final Set<String> KNOWN_KEYS = new HashSet<String>(Arrays.asList(
            "check.metadata", "check.crc", "check.fake-dirs", "check.forged-eocd",
            "check.packsquash", "check.png", "check.ogg", "check.json", "check.strict"));

    /** Read + validate a config file and apply it over {@link CheckOptions#defaults()}. */
    public static CheckOptions load(Path file) throws ConfigException {
        String text = readStrictUtf8(file);
        CheckOptions opts = CheckOptions.defaults();
        apply(text, opts, file.toString(), new ArrayList<String>(), true);
        return opts;
    }

    /**
     * Validate a config file without applying it. Returns a list of problems;
     * an empty list means the file is valid. Never throws.
     */
    public static List<String> validate(Path file) {
        List<String> problems = new ArrayList<String>();
        String text;
        try {
            text = readStrictUtf8(file);
        } catch (ConfigException e) {
            problems.add(e.getMessage());
            return problems;
        }
        try {
            apply(text, CheckOptions.defaults(), file.toString(), problems, false);
        } catch (ConfigException e) {
            problems.add(e.getMessage());
        }
        return problems;
    }

    /** Render effective options as readable lines. */
    public static String describe(CheckOptions o) {
        StringBuilder sb = new StringBuilder();
        sb.append("check.metadata=").append(o.metadata).append('\n');
        sb.append("check.crc=").append(o.crc).append('\n');
        sb.append("check.fake-dirs=").append(o.fakeDirs).append('\n');
        sb.append("check.forged-eocd=").append(o.forgedEocd).append('\n');
        sb.append("check.packsquash=").append(o.packsquash).append('\n');
        sb.append("check.png=").append(o.png).append('\n');
        sb.append("check.ogg=").append(o.ogg).append('\n');
        sb.append("check.json=").append(o.json).append('\n');
        sb.append("check.strict=").append(o.strict);
        return sb.toString();
    }

    // -----------------------------------------------------------------

    private static void apply(String text, CheckOptions opts, String src,
            List<String> problems, boolean throwOnError) throws ConfigException {
        Map<String, Boolean> seen = new HashMap<String, Boolean>();
        String[] lines = text.split("\\r\\n|\\r|\\n", -1);
        for (int i = 0; i < lines.length; i++) {
            int lineNo = i + 1;
            String raw = lines[i];
            String line = raw.trim();
            if (line.isEmpty() || line.charAt(0) == '#' || line.charAt(0) == '!') {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0) {
                problem(problems, throwOnError, src + ":" + lineNo + ": malformed line (expected key=value): " + raw);
                continue;
            }
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            if (key.isEmpty()) {
                problem(problems, throwOnError, src + ":" + lineNo + ": empty key");
                continue;
            }
            if (!KNOWN_KEYS.contains(key)) {
                problem(problems, throwOnError, src + ":" + lineNo + ": unknown key '" + key
                        + "' (known: " + sortedKeys() + ")");
                continue;
            }
            if (seen.put(key, Boolean.TRUE) != null) {
                problem(problems, throwOnError, src + ":" + lineNo + ": duplicate key '" + key + "'");
                continue;
            }
            Boolean b = parseBoolean(value);
            if (b == null) {
                problem(problems, throwOnError, src + ":" + lineNo + ": invalid boolean for '" + key
                        + "': '" + value + "' (use true/false)");
                continue;
            }
            set(opts, key, b.booleanValue());
        }
    }

    private static void problem(List<String> problems, boolean throwOnError, String msg) throws ConfigException {
        if (throwOnError) {
            throw new ConfigException(msg);
        }
        problems.add(msg);
    }

    private static Boolean parseBoolean(String v) {
        if ("true".equalsIgnoreCase(v)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(v)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private static void set(CheckOptions o, String key, boolean v) {
        if ("check.metadata".equals(key)) o.metadata = v;
        else if ("check.crc".equals(key)) o.crc = v;
        else if ("check.fake-dirs".equals(key)) o.fakeDirs = v;
        else if ("check.forged-eocd".equals(key)) o.forgedEocd = v;
        else if ("check.packsquash".equals(key)) o.packsquash = v;
        else if ("check.png".equals(key)) o.png = v;
        else if ("check.ogg".equals(key)) o.ogg = v;
        else if ("check.json".equals(key)) o.json = v;
        else if ("check.strict".equals(key)) o.strict = v;
    }

    private static String sortedKeys() {
        List<String> k = new ArrayList<String>(KNOWN_KEYS);
        java.util.Collections.sort(k);
        return k.toString();
    }

    /** Read a file as strict UTF-8; reject missing, BOM, malformed UTF-8, U+FFFD. */
    private static String readStrictUtf8(Path file) throws ConfigException {
        if (file == null || !Files.exists(file)) {
            throw new ConfigException("config file not found: " + file);
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new ConfigException("cannot read config file " + file + ": " + e.getMessage());
        }
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xEF && (bytes[1] & 0xff) == 0xBB
                && (bytes[2] & 0xff) == 0xBF) {
            throw new ConfigException("config file has a UTF-8 BOM (must be UTF-8 without BOM): " + file);
        }
        for (int i = 0; i + 2 < bytes.length; i++) {
            if ((bytes[i] & 0xff) == 0xEF && (bytes[i + 1] & 0xff) == 0xBF && (bytes[i + 2] & 0xff) == 0xBD) {
                throw new ConfigException("config file contains U+FFFD replacement character: " + file);
            }
        }
        CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            String s = dec.decode(ByteBuffer.wrap(bytes)).toString();
            if (s.indexOf('\uFFFD') >= 0) {
                throw new ConfigException("config file contains U+FFFD replacement character: " + file);
            }
            return s;
        } catch (CharacterCodingException e) {
            throw new ConfigException("config file is not valid UTF-8: " + file + " (" + e.getMessage() + ")");
        }
    }
}
