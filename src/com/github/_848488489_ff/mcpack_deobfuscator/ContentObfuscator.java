package com.github._848488489_ff.mcpack_deobfuscator;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Corrupts the checksums / headers of files stored inside an archive, in a way
 * that {@link ContentDeobfuscator} can restore without knowing any key.
 *
 * <p>This is the inverse of {@link ContentDeobfuscator}: given a clean PNG / Ogg /
 * JSON file, it produces exactly the form that {@link ContentDeobfuscator}
 * restores. {@link ObfuscateEngine} uses it when content-level obfuscation is
 * enabled, so the archive stores files that a strict extractor would
 * additionally reject on the extracted data, while {@link DeobfuscateEngine} puts
 * them back together.
 *
 * <ul>
 *   <li><b>PNG</b> - a random key is XORed into every chunk CRC-32 and into
 *       the Adler-32 at the tail of the IDAT zlib stream. The chunk data is
 *       untouched, so {@link ContentDeobfuscator#fixPng} restores the checksums by
 *       recomputing them (no key needed).
 *   <li><b>Ogg Vorbis</b> - the first page stream serial is rewritten (+1),
 *       every granule is mapped to {@code i64::MIN + granule} (wrapping, so
 *       granule -1 maps to a large positive value) and the Vorbis
 *       identification bitrate fields are forced to -1. Page CRC-32s are
 *       recomputed. {@link ContentDeobfuscator#fixOgg} restores all of it.
 *   <li><b>JSON</b> - all whitespace outside strings is stripped (minified).
 *       {@link ContentDeobfuscator#prettifyJson} restores the layout.
 * </ul>
 */
public final class ContentObfuscator {

    private ContentObfuscator() {
    }

    // -----------------------------------------------------------------
    // PNG
    // -----------------------------------------------------------------

    /**
     * Obfuscate a PNG datastream: XOR a random key into every chunk CRC-32
     * and into the Adler-32 at the tail of the IDAT zlib stream.
     *
     * @param in  the clean PNG bytes
     * @param rnd RNG used to derive the obfuscation key
     * @return the obfuscated bytes, or {@code in} if the input is not PNG
     */
    public static byte[] obfuscatePng(byte[] in, Random rnd) {
        if (in == null || in.length < 33) {
            return in;
        }
        // PNG signature: 89 50 4E 47 0D 0A 1A 0A
        if ((in[0] & 0xff) != 0x89 || in[1] != 'P' || in[2] != 'N' || in[3] != 'G'
                || in[4] != 0x0d || in[5] != 0x0a || in[6] != 0x1a || in[7] != 0x0a) {
            return in;
        }

        byte[] out = in.clone();

        // Collect chunk layout: {typeStart, dataStart, dataLen, crcPos}.
        List<int[]> chunks = new ArrayList<int[]>();
        List<int[]> idats = new ArrayList<int[]>();
        int i = 8;
        while (i + 12 <= out.length) {
            int len = ContentDeobfuscator.be32(out, i);
            if (len < 0 || i + 12 + len > out.length) {
                break;
            }
            int[] c = {i + 4, i + 8, len, i + 8 + len};
            chunks.add(c);
            if (ContentDeobfuscator.isType(out, i + 4, "IDAT")) {
                idats.add(c);
            }
            if (ContentDeobfuscator.isType(out, i + 4, "IEND")) {
                break;
            }
            i += 12 + len;
        }
        if (chunks.isEmpty()) {
            return in;
        }

        int key = rnd.nextInt();
        if (key == 0) {
            key = 1;
        }

        // XOR the key into every chunk CRC-32.
        for (int[] c : chunks) {
            putBe32(out, c[3], ContentDeobfuscator.be32(out, c[3]) ^ key);
        }
        // XOR the key into the Adler-32 at the tail of the last IDAT chunk.
        if (!idats.isEmpty()) {
            int[] last = idats.get(idats.size() - 1);
            int adPos = last[1] + last[2] - 4;
            if (adPos >= 0) {
                putBe32(out, adPos, ContentDeobfuscator.be32(out, adPos) ^ key);
            }
        }
        return out;
    }

    // -----------------------------------------------------------------
    // Ogg Vorbis
    // -----------------------------------------------------------------

    /**
     * Obfuscate an Ogg Vorbis stream: first page serial +1, every granule
     * mapped to {@code i64::MIN + granule}, Vorbis identification bitrate
     * fields forced to -1, page CRC-32s recomputed.
     *
     * @param in the clean Ogg bytes
     * @return the obfuscated bytes, or {@code in} if the input is not Ogg
     */
    public static byte[] obfuscateOgg(byte[] in) {
        if (in == null || in.length < 27 || !ContentDeobfuscator.isOggPage(in, 0)) {
            return in;
        }
        byte[] out = in.clone();
        boolean modified = false;

        List<int[]> pages = new ArrayList<int[]>(); // {start, end}
        int i = 0;
        while (i + 27 <= out.length && ContentDeobfuscator.isOggPage(out, i)) {
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

        // 1. Rewrite the first page serial (+1). Only do this when there is a
        //    second page to recover it from; a single-page stream cannot be
        //    restored by fixOgg, so leave it untouched.
        if (pages.size() >= 2) {
            int s0 = ContentDeobfuscator.le32(out, pages.get(0)[0] + 14);
            putLe32(out, pages.get(0)[0] + 14, s0 + 1);
            modified = true;
        }

        // 2. Map every granule: mangled = i64::MIN + original (wrapping).
        for (int[] p : pages) {
            long g = ContentDeobfuscator.le64(out, p[0] + 6);
            putLe64(out, p[0] + 6, g + Long.MIN_VALUE);
            modified = true;
        }

        // 3. Force the Vorbis identification bitrate fields to -1.
        int[] first = pages.get(0);
        int pstart = first[0] + 27 + (out[first[0] + 26] & 0xff);
        if (pstart + 30 <= first[1]
                && out[pstart] == 1 && ContentDeobfuscator.isStr(out, pstart + 1, "vorbis")) {
            for (int k = 0; k < 3; k++) {
                int pos = pstart + 16 + k * 4;
                if (ContentDeobfuscator.le32(out, pos) != -1) {
                    putLe32(out, pos, -1);
                    modified = true;
                }
            }
        }

        // 4. Recompute every page CRC-32 (field treated as zero).
        for (int[] p : pages) {
            int calc = ContentDeobfuscator.oggCrc(out, p[0], p[1]);
            if (ContentDeobfuscator.le32(out, p[0] + 22) != calc) {
                putLe32(out, p[0] + 22, calc);
                modified = true;
            }
        }
        return modified ? out : in;
    }

    // -----------------------------------------------------------------
    // JSON
    // -----------------------------------------------------------------

    /**
     * Minify JSON content (the inverse of prettify).
     *
     * @param in the JSON bytes (UTF-8)
     * @return the minified bytes, or {@code in} if nothing changed
     */
    public static byte[] minifyJson(byte[] in) {
        return ContentDeobfuscator.minifyJson(in);
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private static void putBe32(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }

    private static void putLe32(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >>> 8);
        b[off + 2] = (byte) (v >>> 16);
        b[off + 3] = (byte) (v >>> 24);
    }

    private static void putLe64(byte[] b, int off, long v) {
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
