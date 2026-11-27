package com.ziptool;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Adler32;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;

/**
 * Content-level repair of files stored inside an archive.
 *
 * The ZIP metadata repair done by {@link RepairEngine} only rebuilds a
 * consistent ZIP container. It does not fix the *bytes of the stored files*.
 * Some damaged archives additionally have corrupt file contents, so that even
 * after the ZIP is rebuilt the extracted files still fail to open. This class
 * repairs those contents:
 *
 * <ul>
 *   <li><b>PNG</b> - a random key was XORed into every chunk CRC-32 and into
 *       the Adler-32 checksum at the tail of the IDAT zlib stream. The chunk
 *       data itself is untouched, so repair is possible without knowing the
 *       key: recompute the zlib Adler-32 by inflating the stream, and
 *       recompute every chunk CRC-32.
 *   <li><b>Ogg Vorbis</b> - the first page stream serial was rewritten (+1),
 *       every granule position mapped to {@code i64::MIN + granule} and the
 *       Vorbis identification header bitrate fields forced to -1. Repair
 *       restores the serial from the second page, un-maps the granules and
 *       rewrites the page CRC-32 (Ogg CRC, polynomial 0x04C11DB7,
 *       non-reflected, initial 0, computed over the page with the CRC field
 *       treated as zero).
 *   <li><b>JSON</b> - re-format (prettify) minified JSON so the file is
 *       readable and editable again.
 *   <li><b>NBT</b> - structural validation of gzip-compressed NBT.
 * </ul>
 */
public final class ContentRepairer {

    private ContentRepairer() {
    }

    // -----------------------------------------------------------------
    // PNG
    // -----------------------------------------------------------------

    /**
     * Repair a PNG datastream whose chunk CRC-32 / IDAT Adler-32 were corrupted.
     *
     * @param in the (possibly corrupted) PNG bytes
     * @return the repaired bytes if anything was changed, otherwise {@code in}
     */
    public static byte[] fixPng(byte[] in) {
        if (in == null || in.length < 33) {
            return in;
        }
        // PNG signature: 89 50 4E 47 0D 0A 1A 0A
        if ((in[0] & 0xff) != 0x89 || in[1] != 'P' || in[2] != 'N' || in[3] != 'G'
                || in[4] != 0x0d || in[5] != 0x0a || in[6] != 0x1a || in[7] != 0x0a) {
            return in;
        }

        byte[] out = in.clone();
        boolean modified = false;

        // First pass: collect chunk layout so multi-IDAT streams can be handled.
        List<int[]> chunks = new ArrayList<int[]>(); // {typeStart, dataStart, dataLen, crcPos}
        int i = 8;
        while (i + 12 <= out.length) {
            int len = be32(out, i);
            if (len < 0 || i + 12 + len > out.length) {
                break;
            }
            chunks.add(new int[]{i + 4, i + 8, len, i + 8 + len});
            if (isType(out, i + 4, "IEND")) {
                break;
            }
            i += 12 + len;
        }

        // Repair the zlib Adler-32 if a full inflate fails.
        List<int[]> idats = new ArrayList<int[]>();
        for (int[] c : chunks) {
            if (isType(out, c[0], "IDAT")) {
                idats.add(c);
            }
        }
        if (!idats.isEmpty()) {
            // Single IDAT: the whole zlib stream is one chunk.
            if (idats.size() == 1) {
                int[] c = idats.get(0);
                int zs = c[1], zl = c[2];
                if (tryInflate(out, zs, zl, false) == null) {
                    // Adler-32 is corrupt: inflate the raw deflate payload
                    // (skip the 2-byte zlib header, drop the 4-byte checksum)
                    // and recompute the checksum.
                    if (zl > 6) {
                        byte[] raw = tryInflate(out, zs + 2, zl - 6, true);
                        if (raw != null) {
                            Adler32 ad = new Adler32();
                            ad.update(raw);
                            putBe32(out, zs + zl - 4, (int) ad.getValue());
                            modified = true;
                        }
                    }
                }
            } else {
                // Multi-IDAT: the zlib stream spans several chunks. Only the
                // tail of the *last* chunk holds the Adler-32; intermediate
                // chunk tails are deflate bytes that may have been corrupted.
                // Attempt the full-stream repair anyway.
                int total = 0;
                for (int[] c : idats) {
                    total += c[2];
                }
                byte[] zlibAll = new byte[total];
                int off = 0;
                for (int[] c : idats) {
                    System.arraycopy(out, c[1], zlibAll, off, c[2]);
                    off += c[2];
                }
                if (tryInflate(zlibAll, 0, total, false) == null) {
                    if (total > 6) {
                        byte[] raw = tryInflate(zlibAll, 2, total - 6, true);
                        if (raw != null) {
                            Adler32 ad = new Adler32();
                            ad.update(raw);
                            int[] last = idats.get(idats.size() - 1);
                            putBe32(out, last[1] + last[2] - 4, (int) ad.getValue());
                            modified = true;
                        }
                    }
                }
            }
        }

        // Recompute every chunk CRC-32 over (type + data).
        for (int[] c : chunks) {
            CRC32 crc = new CRC32();
            crc.update(out, c[0], 4 + c[2]);
            int calc = (int) crc.getValue();
            if (calc != be32(out, c[3])) {
                putBe32(out, c[3], calc);
                modified = true;
            }
        }
        return modified ? out : in;
    }

    /** Inflate {@code len} bytes at {@code off}; null on any failure. */
    private static byte[] tryInflate(byte[] data, int off, int len, boolean nowrap) {
        Inflater inf = new Inflater(nowrap);
        inf.setInput(data, off, len);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        try {
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n > 0) {
                    bos.write(buf, 0, n);
                } else if (inf.needsInput() || inf.needsDictionary()) {
                    break;
                } else if (n == 0 && !inf.finished()) {
                    // No progress and not finished: give up.
                    break;
                }
            }
            return inf.finished() ? bos.toByteArray() : null;
        } catch (DataFormatException e) {
            return null;
        } finally {
            inf.end();
        }
    }

    // -----------------------------------------------------------------
    // Ogg Vorbis
    // -----------------------------------------------------------------

    /**
     * Repair an Ogg Vorbis stream whose first-page serial was rewritten (+1),
     * whose granule positions were mapped to {@code i64::MIN + granule} and
     * whose bitrate fields were forced to -1.
     *
     * @param in the (possibly corrupted) Ogg bytes
     * @return the repaired bytes if anything was changed, otherwise {@code in}
     */
    public static byte[] fixOgg(byte[] in) {
        if (in == null || in.length < 27 || !isOggPage(in, 0)) {
            return in;
        }
        byte[] out = in.clone();
        boolean modified = false;

        List<int[]> pages = new ArrayList<int[]>(); // {start, end}
        int i = 0;
        while (i + 27 <= out.length && isOggPage(out, i)) {
            int nseg = out[i + 26] & 0xff;
            if (i + 27 + nseg > out.length) {
                break;
            }
            int body = 0;
            for (int k = 0; k < nseg; k++) {
                body += out[i + 27 + k] & 0xff;
            }
            int end = i + 27 + nseg + body;
            if (end > out.length) {
                break;
            }
            pages.add(new int[]{i, end});
            i = end;
        }
        if (pages.isEmpty()) {
            return in;
        }

        // 1. Restore the first page stream serial: the mangler only rewrites
        //    the first packet's serial (+1), leaving the rest untouched. If the
        //    first page serial is exactly one greater than the second page's,
        //    rewrite it.
        if (pages.size() >= 2) {
            int s0 = le32(out, pages.get(0)[0] + 14);
            int s1 = le32(out, pages.get(1)[0] + 14);
            if (s0 == s1 + 1) {
                putLe32(out, pages.get(0)[0] + 14, s1);
                modified = true;
            }
        }

        // 2. Un-map granule positions: mangled = i64::MIN + original. A real
        //    Vorbis granule is a sample count (0..2^62 range, or -1 for "none"),
        //    so a value outside [-2^62, +2^62] marks a corrupted page. The
        //    upper bound catches the wrapping case original == -1, which maps
        //    to a large positive value.
        for (int[] p : pages) {
            long g = le64(out, p[0] + 6);
            if (g < -(1L << 62) || g > (1L << 62)) {
                // original = mangled - i64::MIN = mangled + 2^63 (wrapping)
                putLe64(out, p[0] + 6, g + Long.MIN_VALUE);
                modified = true;
            }
        }

        // 3. Restore Vorbis identification header bitrate fields (-1 -> 0).
        int[] first = pages.get(0);
        int pstart = first[0] + 27 + (out[first[0] + 26] & 0xff);
        if (pstart + 30 <= first[1]) {
            // packet_type (1) + "vorbis" (6) + version (4) + channels (1) +
            // sample rate (4) then three signed LE bitrates.
            if (out[pstart] == 1 && isStr(out, pstart + 1, "vorbis")) {
                for (int k = 0; k < 3; k++) {
                    int pos = pstart + 16 + k * 4;
                    int br = le32(out, pos);
                    if (br == -1) {
                        putLe32(out, pos, 0);
                        modified = true;
                    }
                }
            }
        }

        // 4. Recompute every page CRC-32 (field treated as zero).
        for (int[] p : pages) {
            int calc = oggCrc(out, p[0], p[1]);
            if (le32(out, p[0] + 22) != calc) {
                putLe32(out, p[0] + 22, calc);
                modified = true;
            }
        }
        return modified ? out : in;
    }

    /**
     * Ogg page CRC-32: polynomial 0x04C11DB7, non-reflected, initial 0, no
     * final XOR, computed over the whole page with the CRC field (offset 22)
     * treated as zero.
     */
    static int oggCrc(byte[] data, int start, int end) {
        int crc = 0;
        for (int k = start; k < end; k++) {
            int b = data[k] & 0xff;
            int rel = k - start;
            if (rel >= 22 && rel < 26) {
                b = 0;
            }
            crc ^= b << 24;
            for (int j = 0; j < 8; j++) {
                if ((crc & 0x80000000) != 0) {
                    crc = (crc << 1) ^ 0x04C11DB7;
                } else {
                    crc = crc << 1;
                }
            }
        }
        return crc;
    }

    // -----------------------------------------------------------------
    // Feature detection (for the checker)
    // -----------------------------------------------------------------

    /**
     * True if the PNG datastream looks corrupted: at least one
     * chunk CRC-32 does not match the chunk contents. A corrupted file's
     * recomputed CRC never matches the stored one.
     */
    public static boolean isPngCorrupted(byte[] in) {
        if (in == null || in.length < 33
                || (in[0] & 0xff) != 0x89 || in[1] != 'P' || in[2] != 'N' || in[3] != 'G'
                || in[4] != 0x0d || in[5] != 0x0a || in[6] != 0x1a || in[7] != 0x0a) {
            return false;
        }
        int i = 8;
        int checked = 0;
        while (i + 12 <= in.length && checked < 4) {
            int len = be32(in, i);
            if (len < 0 || i + 12 + len > in.length) {
                break;
            }
            CRC32 crc = new CRC32();
            crc.update(in, i + 4, 4 + len);
            if ((int) crc.getValue() != be32(in, i + 8 + len)) {
                return true;
            }
            if (isType(in, i + 4, "IEND")) {
                break;
            }
            i += 12 + len;
            checked++;
        }
        return false;
    }

    /**
     * True if the Ogg stream looks corrupted: a rewritten first
     * page serial (first == second + 1), a granule mapped below -2^62, or
     * Vorbis identification bitrate fields forced to -1.
     */
    public static boolean isOggCorrupted(byte[] in) {
        if (in == null || in.length < 27 || !isOggPage(in, 0)) {
            return false;
        }
        int[] first = pageRange(in, 0);
        if (first == null) {
            return false;
        }
        // Serial: first page == second page + 1.
        int[] second = pageRange(in, first[1]);
        if (second != null) {
            int s0 = le32(in, first[0] + 14);
            int s1 = le32(in, second[0] + 14);
            if (s0 == s1 + 1) {
                return true;
            }
        }
        // Granule: a mapped value is far outside the plausible sample-count
        // range (either a huge negative number, or a huge positive one from
        // the wrapping case original == -1).
        long g = le64(in, first[0] + 6);
        if (g < -(1L << 62) || g > (1L << 62)) {
            return true;
        }
        // Bitrate fields forced to -1 in the Vorbis identification header.
        int pstart = first[0] + 27 + (in[first[0] + 26] & 0xff);
        if (pstart + 28 <= first[1] && in[pstart] == 1 && isStr(in, pstart + 1, "vorbis")) {
            for (int k = 0; k < 3; k++) {
                if (le32(in, pstart + 16 + k * 4) == -1) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Locate the page starting at {@code off}; {start,end} or null. */
    static int[] pageRange(byte[] b, int off) {
        if (off < 0 || off + 27 > b.length || !isOggPage(b, off)) {
            return null;
        }
        int nseg = b[off + 26] & 0xff;
        if (off + 27 + nseg > b.length) {
            return null;
        }
        int body = 0;
        for (int k = 0; k < nseg; k++) {
            body += b[off + 27 + k] & 0xff;
        }
        int end = off + 27 + nseg + body;
        return end <= b.length ? new int[]{off, end} : null;
    }

    // -----------------------------------------------------------------
    // JSON
    // -----------------------------------------------------------------

    /**
     * Re-format minified JSON with two-space indentation.
     *
     * <p>Operates on the UTF-8 decoded string, so multi-byte characters are
     * preserved exactly; only structural whitespace outside strings is
     * re-laid out.
     *
     * @param in the JSON bytes (UTF-8)
     * @return the pretty-printed bytes, or {@code null} if the input is empty
     */
    public static byte[] prettifyJson(byte[] in) {
        if (in == null || in.length == 0) {
            return null;
        }
        String s = new String(in, StandardCharsets.UTF_8);
        if (!s.isEmpty() && s.charAt(0) == '\uFEFF') {
            s = s.substring(1);
        }
        StringBuilder sb = new StringBuilder(s.length() * 2 + 32);
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                sb.append(c);
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                sb.append(c);
                continue;
            }
            if (c == '{' || c == '[') {
                sb.append(c).append('\n').append(indent(depth + 1));
                depth++;
                continue;
            }
            if (c == '}' || c == ']') {
                depth = Math.max(0, depth - 1);
                sb.append('\n').append(indent(depth)).append(c);
                continue;
            }
            if (c == ',') {
                sb.append(',').append('\n').append(indent(depth));
                continue;
            }
            if (c == ':') {
                sb.append(':').append(' ');
                continue;
            }
            if (Character.isWhitespace(c)) {
                continue;
            }
            sb.append(c);
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Minify JSON: strip every whitespace character outside strings.
     *
     * <p>This is the inverse of {@link #prettifyJson} and produces exactly the
     * content that the repair path will re-format. Operates on the UTF-8
     * decoded string, so multi-byte characters are preserved.
     *
     * @param in the JSON bytes (UTF-8)
     * @return the minified bytes, or {@code in} if nothing changed
     */
    public static byte[] minifyJson(byte[] in) {
        if (in == null || in.length == 0) {
            return in;
        }
        String s = new String(in, StandardCharsets.UTF_8);
        if (!s.isEmpty() && s.charAt(0) == '\uFEFF') {
            s = s.substring(1);
        }
        StringBuilder sb = new StringBuilder(s.length());
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                sb.append(c);
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                sb.append(c);
                continue;
            }
            if (Character.isWhitespace(c)) {
                continue;
            }
            sb.append(c);
        }
        byte[] out = sb.toString().getBytes(StandardCharsets.UTF_8);
        return java.util.Arrays.equals(out, in) ? in : out;
    }

    private static String indent(int depth) {
        StringBuilder sb = new StringBuilder(depth * 2);
        for (int i = 0; i < depth * 2; i++) {
            sb.append(' ');
        }
        return sb.toString();
    }

    // -----------------------------------------------------------------
    // NBT
    // -----------------------------------------------------------------

    /**
     * Validate the structure of a gzip-compressed NBT file.
     *
     * @param in the NBT bytes
     * @return {@code null} if the structure is valid, otherwise a description
     *         of the first structural problem found
     */
    public static String checkNbt(byte[] in) {
        if (in == null || in.length < 4) {
            return "NBT too small";
        }
        try {
            GZIPInputStream gz = new GZIPInputStream(new java.io.ByteArrayInputStream(in));
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = gz.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            gz.close();
            byte[] raw = bos.toByteArray();
            NbtReader r = new NbtReader(raw);
            String err = r.readRoot();
            if (err != null) {
                return err;
            }
            return null;
        } catch (IOException e) {
            return "NBT gzip error: " + e.getMessage();
        } catch (Exception e) {
            return "NBT error: " + e.getMessage();
        }
    }

    /** Minimal big-endian NBT reader used for structural validation. */
    private static final class NbtReader {
        private final byte[] b;
        private int p;

        NbtReader(byte[] b) {
            this.b = b;
        }

        String readRoot() {
            if (p + 1 > b.length) {
                return "truncated root type";
            }
            int type = b[p++] & 0xff;
            if (type != 10) {
                return "root is not TAG_Compound (type " + type + ")";
            }
            String name = readName();
            if (name == null) {
                return "truncated root name";
            }
            return readPayload(10);
        }

        private String readPayload(int type) {
            switch (type) {
                case 1: return skip(1);
                case 2: return skip(2);
                case 3: return skip(4);
                case 4: return skip(8);
                case 5: return skip(4);
                case 6: return skip(8);
                case 7: { // Byte array
                    if (p + 4 > b.length) return "truncated byte-array length";
                    int len = be32(b, p);
                    p += 4;
                    if (len < 0 || p + len > b.length) return "bad byte-array length";
                    p += len;
                    return null;
                }
                case 8: { // String
                    if (p + 2 > b.length) return "truncated string length";
                    int len = (b[p] & 0xff) << 8 | (b[p + 1] & 0xff);
                    p += 2;
                    if (p + len > b.length) return "bad string length";
                    p += len;
                    return null;
                }
                case 9: { // List
                    if (p + 5 > b.length) return "truncated list header";
                    int elemType = b[p++] & 0xff;
                    int len = be32(b, p);
                    p += 4;
                    if (len < 0) return "bad list length";
                    for (int i = 0; i < len; i++) {
                        String e = readPayload(elemType);
                        if (e != null) return e;
                    }
                    return null;
                }
                case 10: { // Compound: tags until TAG_End
                    while (true) {
                        if (p + 1 > b.length) return "truncated compound tag";
                        int t = b[p++] & 0xff;
                        if (t == 0) return null;
                        String nm = readName();
                        if (nm == null) return "truncated compound name";
                        String e = readPayload(t);
                        if (e != null) return e;
                    }
                }
                case 11: { // Int array
                    if (p + 4 > b.length) return "truncated int-array length";
                    int len = be32(b, p);
                    p += 4;
                    if (len < 0 || p + len * 4 > b.length) return "bad int-array length";
                    p += len * 4;
                    return null;
                }
                case 12: { // Long array
                    if (p + 4 > b.length) return "truncated long-array length";
                    int len = be32(b, p);
                    p += 4;
                    if (len < 0 || p + len * 8 > b.length) return "bad long-array length";
                    p += len * 8;
                    return null;
                }
                default:
                    return "unknown tag type " + type;
            }
        }

        private String readName() {
            if (p + 2 > b.length) return null;
            int len = (b[p] & 0xff) << 8 | (b[p + 1] & 0xff);
            p += 2;
            if (p + len > b.length) return null;
            p += len;
            return "";
        }

        private String skip(int n) {
            if (p + n > b.length) return "truncated tag";
            p += n;
            return null;
        }
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    static boolean isType(byte[] b, int off, String s) {
        return b[off] == (byte) s.charAt(0) && b[off + 1] == (byte) s.charAt(1)
                && b[off + 2] == (byte) s.charAt(2) && b[off + 3] == (byte) s.charAt(3);
    }

    static boolean isStr(byte[] b, int off, String s) {
        for (int i = 0; i < s.length(); i++) {
            if (b[off + i] != (byte) s.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    static boolean isOggPage(byte[] b, int off) {
        return off + 4 <= b.length && b[off] == 'O' && b[off + 1] == 'g'
                && b[off + 2] == 'g' && b[off + 3] == 'S';
    }

    static int be32(byte[] b, int off) {
        return (b[off] & 0xff) << 24 | (b[off + 1] & 0xff) << 16
                | (b[off + 2] & 0xff) << 8 | (b[off + 3] & 0xff);
    }

    static void putBe32(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }

    static int le32(byte[] b, int off) {
        return (b[off] & 0xff) | (b[off + 1] & 0xff) << 8
                | (b[off + 2] & 0xff) << 16 | (b[off + 3] & 0xff) << 24;
    }

    static void putLe32(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >>> 8);
        b[off + 2] = (byte) (v >>> 16);
        b[off + 3] = (byte) (v >>> 24);
    }

    static long le64(byte[] b, int off) {
        return (b[off] & 0xffL) | (b[off + 1] & 0xffL) << 8
                | (b[off + 2] & 0xffL) << 16 | (b[off + 3] & 0xffL) << 24
                | (b[off + 4] & 0xffL) << 32 | (b[off + 5] & 0xffL) << 40
                | (b[off + 6] & 0xffL) << 48 | (b[off + 7] & 0xffL) << 56;
    }

    static void putLe64(byte[] b, int off, long v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >>> 8);
        b[off + 2] = (byte) (v >>> 16);
        b[off + 3] = (byte) (v >>> 24);
        b[off + 4] = (byte) (v >>> 32);
        b[off + 5] = (byte) (v >>> 40);
        b[off + 6] = (byte) (v >>> 48);
        b[off + 7] = (byte) (v >>> 56);
    }
}
