package com.mcpackdeobfuscator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Obfuscation engine - the inverse of RepairEngine.
 *
 * Produces a ZIP whose local file headers disagree with the central directory
 * in a way that JVM-based readers tolerate but strict extractors (7-Zip,
 * WinRAR, Windows Explorer, python zipfile) reject.
 *
 * Method: build a normal ZIP with ZipOutputStream, then rewrite metadata in
 * place. The central directory keeps the original names, so java.util.zip
 * .ZipFile (which locates entries via the central directory and never compares
 * local header names) still reads every byte, while strict validators abort on
 * the mismatch.
 *
 * The exact behavior is driven by {@link ObfuscateOptions}, a tunable set of
 * parameters (name rewrite mode, percentage, forged EOCD, CRC perturbation,
 * RNG seed). Named {@link Preset}s are quick shortcuts for common
 * configurations.
 */
public final class ObfuscateEngine {

    private ObfuscateEngine() {
    }

    /** Tunable protection parameters (the "bunch of parameters"). */
    public static class ObfuscateOptions {

        /** How to rewrite the file name stored in a local file header. */
        public enum NameMode {
            /** Leave names untouched. */
            NONE,
            /** Uppercase ASCII letters. */
            UPPER,
            /** Uppercase + cyclically shift digits. */
            DIGIT,
            /** Uppercase + randomize letters (same charset, same length). */
            RANDOM
        }

        /** Name rewrite mode. */
        public NameMode nameMode = NameMode.UPPER;
        /** Percentage of entries whose name is rewritten (0-100). */
        public int namePercent = 100;
        /** Percentage of entries disguised as directories (0-100). */
        public int fakeDirPercent = 0;
        /** Prepend a forged EOCD signature at offset 0. */
        public boolean fakeEocd = false;
        /** Randomize the central directory size fields (local header keeps the
         *  real values, so the JVM still reads the data while strict
         *  extractors that compare both metadata blocks reject the archive). */
        public boolean perturbSizes = true;
        /** Randomize CRC-32 fields (local header + central directory). */
        public boolean perturbCrc = false;
        /** Obfuscate the file contents of PNG entries. */
        public boolean obfuscatePng = false;
        /** Obfuscate the file contents of Ogg / Oga entries. */
        public boolean obfuscateOgg = false;
        /** Minify JSON / mcmeta / jsonc / jem / jpm entries. */
        public boolean obfuscateJson = false;
        /** RNG seed for reproducible output. */
        public long seed = 0x5EEDL;
        /** Entry names (or path prefixes) left untouched by protection. */
        public final List<String> whitelist = new ArrayList<String>();

        /** True if any content-obfuscation type is enabled. */
        public boolean anyContent() {
            return obfuscatePng || obfuscateOgg || obfuscateJson;
        }
    }

    /** Outcome of a protection run. */
    public static final class ObfuscateResult {
        /** Entries written. */
        public int entries = 0;
        /** Entries whose local header name was rewritten. */
        public int modified = 0;
        /** PNG files whose chunk CRC / IDAT Adler-32 was obfuscated. */
        public int pngObfuscated = 0;
        /** Ogg files whose headers were obfuscated. */
        public int oggObfuscated = 0;
        /** JSON files that were minified. */
        public int jsonMinified = 0;
        /** Total bytes of the transformed archive. */
        public long bytes = 0;
        /** Whitelist entries that do not exist in the source archive. */
        public final List<String> missingWhitelist = new ArrayList<String>();
    }

    /** Obfuscate using explicit tunable options. */
    public static ObfuscateResult obfuscate(Path input, Path output, ObfuscateOptions opts) throws IOException {
        // 1. Read every entry of the source into memory.
        List<String> names = new ArrayList<String>();
        List<byte[]> datas = new ArrayList<byte[]>();
        try (ZipFile zf = new ZipFile(input.toFile())) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) {
                    continue;
                }
                names.add(e.getName());
                try (InputStream in = zf.getInputStream(e)) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        bos.write(buf, 0, n);
                    }
                    datas.add(bos.toByteArray());
                }
            }
        }

        // Validate whitelist entries against the actual archive contents.
        // A whitelist entry must match at least one entry name exactly or as a
        // path prefix; otherwise the configuration is wrong and the output
        // would silently leave transformed entries untransformed.
        if (!opts.whitelist.isEmpty()) {
            StringBuilder missing = new StringBuilder();
            for (String w : opts.whitelist) {
                boolean found = false;
                for (String n : names) {
                    if (n.equals(w) || n.startsWith(w)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    if (missing.length() > 0) {
                        missing.append(", ");
                    }
                    missing.append(w);
                }
            }
            if (missing.length() > 0) {
                throw new IllegalArgumentException(
                        "whitelist entries not found in archive: " + missing);
            }
        }

        // 2. Emit a fully consistent ZIP into memory. When content
        //    obfuscation is enabled, PNG / Ogg / JSON entries are obfuscated
        //    on the fly; ZipOutputStream recomputes the metadata so the
        //    container stays internally consistent and only the stored file
        //    bytes differ. The content RNG is seeded independently so the
        //    metadata perturbation below stays reproducible on its own.
        Random contentRnd = new Random(opts.seed ^ 0x0B0B5EEDL);
        int[] obfCount = new int[3]; // png, ogg, json
        ByteArrayOutputStream norm = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(norm)) {
            for (int i = 0; i < names.size(); i++) {
                String name = names.get(i);
                byte[] d = datas.get(i);
                if (opts.anyContent() && !isWhitelisted(name, opts.whitelist)) {
                    d = obfuscateContent(name, d, contentRnd, obfCount, opts);
                }
                ZipEntry e = new ZipEntry(name);
                zos.putNextEntry(e);
                zos.write(d);
                zos.closeEntry();
            }
        }
        byte[] b = norm.toByteArray();

        int eocd = findEocd(b);
        int cdOff = u32(b, eocd + 16);
        int count = u16(b, eocd + 10);

        // 3a. ZipOutputStream emits entries with a data descriptor, which makes
        //     the local header size fields zero. Strict extractors skip the
        //     size check in that case, which would defeat the protection, so
        //     backfill the real values into every local header and clear the
        //     descriptor flag. The JVM keeps reading via the local header
        //     values, so the archive still opens in Java applications.
        stripDataDescriptors(b, cdOff, count);

        // 3b. Rewrite metadata via central directory offsets.
        //     Whitelisted entries are left completely untouched.
        Random rnd = new Random(opts.seed);
        int modified = 0;
        boolean[] fakeDir = new boolean[count];
        int p = cdOff;
        for (int i = 0; i < count && p + 46 <= b.length; i++) {
            int nlen = u16(b, p + 28);
            int elen = u16(b, p + 30);
            int clen = u16(b, p + 32);
            long lho = u32(b, p + 42) & 0xffffffffL;
            String cdName = new String(b, p + 46, nlen, StandardCharsets.UTF_8);
            boolean wl = isWhitelisted(cdName, opts.whitelist);

            if (!wl && lho + 30 <= b.length && isLocalSig(b, (int) lho)) {
                int lnlen = u16(b, (int) lho + 26);
                boolean doName = opts.nameMode != ObfuscateOptions.NameMode.NONE
                        && rnd.nextInt(100) < opts.namePercent;
                if (doName) {
                    for (int k = 0; k < lnlen; k++) {
                        b[(int) lho + 30 + k] = mutate(b[(int) lho + 30 + k], opts.nameMode, rnd);
                    }
                    modified++;
                }
                if (opts.perturbSizes) {
                    int cs = (int) (u32(b, p + 20) & 0xffffffffL);
                    int us = (int) (u32(b, p + 24) & 0xffffffffL);
                    put32(b, p + 20, cs + 1 + rnd.nextInt(50));
                    put32(b, p + 24, us + 1 + rnd.nextInt(50));
                }
                if (opts.perturbCrc) {
                    int fake = rnd.nextInt();
                    put32(b, (int) lho + 14, fake);
                    put32(b, p + 16, fake);
                }
            }
            if (!wl && opts.fakeDirPercent > 0 && rnd.nextInt(100) < opts.fakeDirPercent) {
                fakeDir[i] = true;
            }
            p += 46 + nlen + elen + clen;
        }

        // 4. Disguise selected files as directories (rebuild the central
        //    directory with a trailing '/' and the DOS directory attribute).
        if (opts.fakeDirPercent > 0) {
            b = rebuildCentralDirectory(b, cdOff, count, fakeDir);
        }

        // 5. Optionally prepend a forged EOCD signature.
        if (opts.fakeEocd) {
            b = prependForgedEocd(b);
        }

        Files.write(output, b);
        ObfuscateResult r = new ObfuscateResult();
        r.entries = count;
        r.modified = modified;
        r.pngObfuscated = obfCount[0];
        r.oggObfuscated = obfCount[1];
        r.jsonMinified = obfCount[2];
        r.bytes = b.length;
        return r;
    }

    /**
     * Apply content obfuscation to a single entry when it is a known file
     * type (PNG / Ogg / JSON). Returns the (possibly new) bytes; {@code count}
     * is a 3-element {png, ogg, json} counter incremented on change.
     */
    private static byte[] obfuscateContent(String name, byte[] data, Random rnd,
            int[] count, ObfuscateOptions opts) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (opts.obfuscatePng && lower.endsWith(".png")) {
            byte[] o = ContentObfuscator.obfuscatePng(data, rnd);
            if (o != data) {
                count[0]++;
                return o;
            }
            return data;
        }
        if (opts.obfuscateOgg && (lower.endsWith(".ogg") || lower.endsWith(".oga"))) {
            byte[] o = ContentObfuscator.obfuscateOgg(data);
            if (o != data) {
                count[1]++;
                return o;
            }
            return data;
        }
        if (opts.obfuscateJson && (lower.endsWith(".json") || lower.endsWith(".mcmeta")
                || lower.endsWith(".jsonc") || lower.endsWith(".mcmetac")
                || lower.endsWith(".jem") || lower.endsWith(".jpm"))) {
            byte[] o = ContentObfuscator.minifyJson(data);
            if (o != data) {
                count[2]++;
                return o;
            }
            return data;
        }
        return data;
    }

    /**
     * Backfill the real CRC / sizes into every local file header and clear the
     * data-descriptor flag. ZipOutputStream emits a descriptor (local header
     * size fields zero), which strict extractors tolerate by skipping the size
     * comparison; a fully-populated local header is what makes the divergence
     * between the local header and the central directory actually detectable.
     */
    private static void stripDataDescriptors(byte[] b, int cdOff, int count) {
        int p = cdOff;
        for (int i = 0; i < count && p + 46 <= b.length; i++) {
            int nlen = u16(b, p + 28);
            int elen = u16(b, p + 30);
            int clen = u16(b, p + 32);
            long lho = u32(b, p + 42) & 0xffffffffL;
            if (lho + 30 <= b.length && isLocalSig(b, (int) lho)) {
                int flag = u16(b, (int) lho + 6);
                if ((flag & 8) != 0) {
                    int crc = (int) (u32(b, p + 16) & 0xffffffffL);
                    int csize = (int) (u32(b, p + 20) & 0xffffffffL);
                    int usize = (int) (u32(b, p + 24) & 0xffffffffL);
                    put16(b, (int) lho + 6, flag & ~8);
                    put32(b, (int) lho + 14, crc);
                    put32(b, (int) lho + 18, csize);
                    put32(b, (int) lho + 22, usize);
                }
            }
            p += 46 + nlen + elen + clen;
        }
    }

    /** True if the entry name matches a whitelist entry exactly or by prefix. */
    private static boolean isWhitelisted(String name, List<String> whitelist) {
        for (String w : whitelist) {
            String t = w.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (name.equals(t) || name.startsWith(t)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Rebuild the central directory, appending '/' to the names of entries
     * marked as fake directories and setting the DOS directory attribute.
     * The local headers and data are left in place.
     */
    private static byte[] rebuildCentralDirectory(byte[] b, int cdOff, int count, boolean[] fakeDir) {
        int eocd = findEocd(b);
        if (eocd < 0) {
            return b;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int p = cdOff;
        for (int i = 0; i < count && p + 46 <= b.length; i++) {
            int nlen = u16(b, p + 28);
            int elen = u16(b, p + 30);
            int clen = u16(b, p + 32);
            int entryLen = 46 + nlen + elen + clen;

            if (fakeDir[i]) {
                // Header (with incremented name length + directory attribute).
                byte[] hdr = new byte[46];
                System.arraycopy(b, p, hdr, 0, 46);
                put16(hdr, 28, nlen + 1);
                int attr = (int) u32(hdr, 38) & 0xffffffff;
                put32(hdr, 38, attr | 0x10); // DOS directory bit
                out.write(hdr, 0, 46);
                out.write(b, p + 46, nlen);  // original name
                out.write('/');              // trailing slash
                out.write(b, p + 46 + nlen, elen + clen);
            } else {
                out.write(b, p, entryLen);
            }
            p += entryLen;
        }
        byte[] cd = out.toByteArray();

        // Assemble: [0..cdOff) + new CD + updated EOCD.
        byte[] nb = new byte[cdOff + cd.length + 22];
        System.arraycopy(b, 0, nb, 0, cdOff);
        System.arraycopy(cd, 0, nb, cdOff, cd.length);
        int neocd = cdOff + cd.length;
        System.arraycopy(b, eocd, nb, neocd, 22);
        put32(nb, neocd + 12, cd.length); // central directory size
        return nb;
    }

    /** Rewrite a single name byte according to the requested mode. */
    private static byte mutate(byte c, ObfuscateOptions.NameMode mode, Random rnd) {
        int v = c & 0xff;
        if (mode == ObfuscateOptions.NameMode.UPPER
                || mode == ObfuscateOptions.NameMode.DIGIT
                || mode == ObfuscateOptions.NameMode.RANDOM) {
            if (v >= 'a' && v <= 'z') {
                v = v - 'a' + 'A';
            }
        }
        if (mode == ObfuscateOptions.NameMode.DIGIT
                || mode == ObfuscateOptions.NameMode.RANDOM) {
            if (v >= '0' && v <= '9') {
                v = '0' + ((v - '0' + 1) % 10);
            }
        }
        if (mode == ObfuscateOptions.NameMode.RANDOM) {
            if (v >= 'A' && v <= 'Z') {
                v = 'A' + rnd.nextInt(26);
            }
        }
        return (byte) v;
    }

    private static boolean isLocalSig(byte[] b, int off) {
        return b[off] == 'P' && b[off + 1] == 'K' && b[off + 2] == 3 && b[off + 3] == 4;
    }

    /** Locate the end-of-central-directory record (searched from the tail). */
    private static int findEocd(byte[] b) {
        int i = b.length - 22;
        while (i >= 0 && !(b[i] == 'P' && b[i + 1] == 'K'
                && b[i + 2] == 5 && b[i + 3] == 6)) {
            i--;
        }
        return i;
    }

    /** Prepend a forged EOCD signature and bump all recorded offsets by 4. */
    private static byte[] prependForgedEocd(byte[] b) {
        int eocd = findEocd(b);
        if (eocd < 0) {
            return b;
        }
        int cdOff = u32(b, eocd + 16);
        int count = u16(b, eocd + 10);

        int p = cdOff;
        for (int i = 0; i < count && p + 46 <= b.length; i++) {
            int nlen = u16(b, p + 28);
            int elen = u16(b, p + 30);
            int clen = u16(b, p + 32);
            long off = u32(b, p + 42) & 0xffffffffL;
            put32(b, p + 42, (int) (off + 4));
            p += 46 + nlen + elen + clen;
        }
        put32(b, eocd + 16, cdOff + 4);

        byte[] nb = new byte[b.length + 4];
        nb[0] = 'P';
        nb[1] = 'K';
        nb[2] = 5;
        nb[3] = 6;
        System.arraycopy(b, 0, nb, 4, b.length);
        return nb;
    }

    private static int u16(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8);
    }

    private static int u32(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8)
                | ((b[off + 2] & 0xff) << 16) | ((b[off + 3] & 0xff) << 24);
    }

    private static void put32(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xff);
        b[off + 1] = (byte) ((v >>> 8) & 0xff);
        b[off + 2] = (byte) ((v >>> 16) & 0xff);
        b[off + 3] = (byte) ((v >>> 24) & 0xff);
    }

    private static void put16(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xff);
        b[off + 1] = (byte) ((v >>> 8) & 0xff);
    }
}
