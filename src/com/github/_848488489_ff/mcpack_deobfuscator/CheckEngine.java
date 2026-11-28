package com.github._848488489_ff.mcpack_deobfuscator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Comprehensive integrity checker.
 *
 * Inspects an archive for two distinct classes of problems:
 *   - obfuscation (data that cannot be decompressed, CRC mismatches, unreadable
 *     structure)
 *   - protection / "encryption" (deliberate metadata divergence between the
 *     central directory and the local file headers, fake directory entries,
 *     forged EOCD signatures) that makes strict extractors fail while the JVM
 *     still reads the archive.
 *
 * The report is produced as localized detail lines plus a verdict key.
 */
public final class CheckEngine {

    private CheckEngine() {
    }

    /** Outcome of a full check. */
    public static final class CheckResult {
        /** Whether the JVM could open the archive at all. */
        public boolean jvmReadable = false;
        /** Number of central directory entries. */
        public int entryCount = 0;
        /** Entries whose local header name differs from the central directory. */
        public int nameMismatch = 0;
        /** Entries whose local header sizes differ from the central directory. */
        public int sizeMismatch = 0;
        /** Entries whose local header CRC differs from the central directory. */
        public int crcFieldMismatch = 0;
        /** Entries marked as directories but pointing at real data. */
        public int fakeDirs = 0;
        /** Forged EOCD signature found at file offset 0. */
        public boolean forgedEocdAt0 = false;
        /** Entries whose data failed the CRC-32 check. */
        public int badCrc = 0;
        /** Entries that could not be decompressed at all. */
        public int unreadable = 0;
        /** Content-obfuscation fingerprint: entries whose local and central
         *  CRC fields match each other but both disagree with the real data
         *  CRC. This is characteristic of content obfuscation, which XORs a
         *  single key into both fields. */
        public int packSquashCrcFingerprint = 0;
        /** PNG files whose chunk CRC-32 does not match their contents. */
        public int packSquashPng = 0;
        /** Ogg files with obfuscated headers (serial / granule / bitrate). */
        public int packSquashOgg = 0;
        /** JSON files that appear minified (no line breaks). */
        public int packSquashJson = 0;
        /** Sample name of an obfuscated PNG, for the report. */
        public String packSquashPngSample = "";
        /** True if content-obfuscation features were detected. */
        public boolean contentObfuscation = false;
        /** Localized detail lines (a summary per anomaly class). */
        public final List<String> details = new ArrayList<String>();
        /** Verdict key: check.ok / check.protected / check.damaged /
         *  check.packsquash. */
        public String verdictKey = "check.ok";
    }

    /**
     * Run a full check on an archive.
     *
     * @param input the archive to inspect
     * @param lang  language used for the localized detail lines
     * @return the check result
     * @throws IOException if the file cannot be read
     */
    public static CheckResult check(Path input, Lang lang) throws IOException {
        return check(input, lang, CheckOptions.defaults());
    }

/**
     * Run a full check on an archive with explicit {@link CheckOptions}.
     *
     * @param opts which checks to run (null = {@link CheckOptions#defaults()});
     *             no configuration file is required
     */
    public static CheckResult check(Path input, Lang lang, CheckOptions opts) throws IOException {
        CheckResult r = new CheckResult();
        if (opts == null) {
            opts = CheckOptions.defaults();
        }
        final CheckOptions o = opts;

        // 1. Header / forged EOCD.
        byte[] head = new byte[4];
        try (InputStream in = Files.newInputStream(input)) {
            if (in.read(head) != 4) {
                throw new IOException("File is too small: " + input);
            }
        }
        if (head[0] == 'P' && head[1] == 'K' && head[2] == 5 && head[3] == 6) {
            r.forgedEocdAt0 = true;
            if (o.forgedEocd) {
                r.details.add(Lang.tr(lang, "check.forgedeocd"));
            }
        }

        byte[] data = Files.readAllBytes(input);
        int eocd = findValidEocd(data);
        if (eocd < 0) {
            r.details.add(Lang.tr(lang, "check.noeocd"));
            r.verdictKey = "check.damaged";
            return r;
        }
        int cdOff = u32(data, eocd + 16);
        int cdSize = u32(data, eocd + 12);
        int count = u16(data, eocd + 10);
        // Smart correction: some protected archives store the central-directory
        // signature a few bytes away from the recorded cdOff. Snap cdOff to the
        // real signature so the traversal below and the metadata comparison stay
        // accurate, and shrink cdSize by the same offset.
        int sigPos = cdSignaturePos(data, cdOff);
        if (sigPos >= 0 && sigPos != cdOff) {
            int delta = sigPos - cdOff;
            cdOff = sigPos;
            cdSize = Math.max(0, cdSize - delta);
        }
        r.entryCount = count;

        // Report the located EOCD and any inconsistency in its bookkeeping.
        r.details.add(Lang.tr(lang, "check.eocd.info", eocd, cdOff, cdSize, count));
        int commentLen = u16(data, eocd + 20);
        int trailing = data.length - eocd - 22 - commentLen;
        if (trailing > 0) {
            r.details.add(Lang.tr(lang, "check.trailing", trailing));
        }
        boolean cdSigOk = cdSize == 0
                || (cdOff + 4 <= data.length && isCdSig(data, cdOff));
        if (!cdSigOk) {
            r.details.add(Lang.tr(lang, "check.badcd"));
            r.verdictKey = "check.damaged";
        }

        // 2. Central directory vs local header metadata comparison.
        int p = cdOff;
        int seen = 0;
        List<String> nameSamples = new ArrayList<String>();
        List<String> fakeSamples = new ArrayList<String>();
        // Per central-directory-entry flag: local CRC field == central CRC field.
        List<Boolean> localCrcMatchesCentral = new ArrayList<Boolean>();
        for (int i = 0; i < count && p + 46 <= data.length && isCdSig(data, p); i++) {
            seen = i + 1;
            int nlen = u16(data, p + 28);
            int elen = u16(data, p + 30);
            int clen = u16(data, p + 32);
            if (p + 46 + nlen + elen + clen > data.length) {
                break;
            }
            long cdCrc = u32(data, p + 16) & 0xffffffffL;
            long cdCsize = u32(data, p + 20) & 0xffffffffL;
            long cdUsize = u32(data, p + 24) & 0xffffffffL;
            long lho = u32(data, p + 42) & 0xffffffffL;

            String cdName = new String(data, p + 46, nlen, java.nio.charset.StandardCharsets.UTF_8);
            localCrcMatchesCentral.add(Boolean.FALSE);

            // Smart local-header location: the recorded offset may be a few
            // bytes off (some protected archives carry a small prefix before
            // the local header too), so accept the PK\x03\x04 signature within
            // ±8 bytes of the recorded offset.
            int lhoPos = localSigPos(data, lho);
            if (lhoPos >= 0) {
                int flag = u16(data, lhoPos + 6);
                long lcrc = u32(data, lhoPos + 14) & 0xffffffffL;
                long lcsize = u32(data, lhoPos + 18) & 0xffffffffL;
                long lusize = u32(data, lhoPos + 22) & 0xffffffffL;
                int lnlen = u16(data, lhoPos + 26);
                if (lhoPos + 30 + lnlen > data.length) {
                    r.unreadable++;
                    r.details.add(Lang.tr(lang, "check.badlocal", cdName));
                    p += 46 + nlen + elen + clen;
                    continue;
                }
                String lname = new String(data, lhoPos + 30, lnlen,
                        java.nio.charset.StandardCharsets.UTF_8);

                if (!cdName.equals(lname)) {
                    r.nameMismatch++;
                    if (nameSamples.size() < 5) {
                        nameSamples.add(cdName);
                    }
                }
                // A real file disguised as a directory.
                if (cdName.endsWith("/") && !lname.endsWith("/")) {
                    r.fakeDirs++;
                    if (fakeSamples.size() < 5) {
                        fakeSamples.add(cdName);
                    }
                }
                // Compare sizes/CRC only when the local header actually records
                // them (a data-descriptor flag means they are absent there).
                boolean hasDataDescriptor = (flag & 8) != 0;
                if (!hasDataDescriptor) {
                    if (lcsize != cdCsize || lusize != cdUsize) {
                        r.sizeMismatch++;
                    }
                    if (lcrc != cdCrc) {
                        r.crcFieldMismatch++;
                    } else {
                        localCrcMatchesCentral.set(i, Boolean.TRUE);
                    }
                }
            } else {
                r.unreadable++;
                r.details.add(Lang.tr(lang, "check.badlocal", cdName));
            }
            p += 46 + nlen + elen + clen;
        }
        // The EOCD entry count may be larger than the records actually present
        // (some protected archives carry a stale count); report what we saw.
        r.entryCount = seen;

        if (o.metadata && r.nameMismatch > 0) {
            r.details.add(Lang.tr(lang, "check.namemismatch", r.nameMismatch, join(nameSamples)));
        }
        if (o.metadata && r.sizeMismatch > 0) {
            r.details.add(Lang.tr(lang, "check.sizemismatch", r.sizeMismatch));
        }
        if (o.metadata && r.crcFieldMismatch > 0) {
            r.details.add(Lang.tr(lang, "check.crcfield", r.crcFieldMismatch));
        }
        if (o.fakeDirs && r.fakeDirs > 0) {
            r.details.add(Lang.tr(lang, "check.fakedirs", r.fakeDirs, join(fakeSamples)));
        }

        // 3. Data integrity: decompress each entry and verify the CRC-32, and
        //    scan a sample of PNG / Ogg / JSON entries for content-obfuscation
        //    features.
        try (ZipFile zf = new ZipFile(input.toFile())) {
            r.jvmReadable = true;
            Enumeration<? extends ZipEntry> en = zf.entries();
            int idx = 0;
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                idx++;
                if (e.isDirectory()) {
                    continue;
                }
                String lname = e.getName().toLowerCase(Locale.ROOT);
                boolean wantPng = o.png && lname.endsWith(".png");
                boolean wantOgg = o.ogg && (lname.endsWith(".ogg") || lname.endsWith(".oga"));
                boolean wantJson = o.json && (lname.endsWith(".json") || lname.endsWith(".mcmeta"));
                try {
                    InputStream in = zf.getInputStream(e);
                    CRC32 crc = new CRC32();
                    // Only buffer entries of a reasonable size for content
                    // feature detection; skip huge files to bound memory use.
                    long size = e.getSize();
                    boolean keep = (wantPng || wantOgg || wantJson)
                            && (size < 0 || size <= 32L * 1024 * 1024);
                    ByteArrayOutputStream bufKeep = keep ? new ByteArrayOutputStream() : null;
                    byte[] buf = new byte[16384];
                    int n;
                    try {
                        while ((n = in.read(buf)) != -1) {
                            crc.update(buf, 0, n);
                            if (bufKeep != null) {
                                bufKeep.write(buf, 0, n);
                            }
                        }
                    } finally {
                        in.close();
                    }
                    long expected = e.getCrc();
                    if (o.crc && expected != -1 && crc.getValue() != expected) {
                        r.badCrc++;
                        // Content-obfuscation fingerprint: the local and central
                        // CRC fields agree with each other (both were XORed
                        // with the same key) but disagree with the real data
                        // CRC.
                        if (idx - 1 < localCrcMatchesCentral.size()
                                && localCrcMatchesCentral.get(idx - 1)) {
                            r.packSquashCrcFingerprint++;
                        }
                    }
                    if (bufKeep != null) {
                        byte[] d = bufKeep.toByteArray();
                        if (wantPng) {
                            if (ContentDeobfuscator.isPngObfuscated(d)) {
                                r.packSquashPng++;
                                if (r.packSquashPngSample.isEmpty()) {
                                    r.packSquashPngSample = e.getName();
                                }
                            }
                        } else if (wantOgg) {
                            if (ContentDeobfuscator.isOggObfuscated(d)) {
                                r.packSquashOgg++;
                            }
                        } else if (wantJson) {
                            if (isMinifiedJson(d)) {
                                r.packSquashJson++;
                            }
                        }
                    }
                } catch (Exception ex) {
                    r.unreadable++;
                    if (r.details.size() < 30) {
                        r.details.add(Lang.tr(lang, "check.unreadable", e.getName(), ex.getMessage()));
                    }
                }
            }
        } catch (ZipException ze) {
            r.jvmReadable = false;
            r.details.add(Lang.tr(lang, "check.notzip", ze.getMessage()));
        }

        // 3b. Summarize content-obfuscation feature findings.
        if (o.packsquash && (r.packSquashCrcFingerprint > 0 || r.packSquashPng > 0
                || r.packSquashOgg > 0)) {
            r.contentObfuscation = true;
            if (r.packSquashPng > 0) {
                r.details.add(Lang.tr(lang, "check.packsquash.png",
                        r.packSquashPng, r.packSquashPngSample));
            }
            if (r.packSquashOgg > 0) {
                r.details.add(Lang.tr(lang, "check.packsquash.ogg", r.packSquashOgg));
            }
            if (r.packSquashCrcFingerprint > 0) {
                r.details.add(Lang.tr(lang, "check.packsquash.crc", r.packSquashCrcFingerprint));
            }
            if (r.packSquashJson > 0) {
                r.details.add(Lang.tr(lang, "check.packsquash.json", r.packSquashJson));
            }
        }

        // 4. Verdict. Protection features take precedence: a protected archive
        //    may legitimately be unreadable by strict validators (that is the
        //    point of protection), so it is reported as protected, not damaged.
        boolean protection = (o.metadata && (r.nameMismatch > 0 || r.sizeMismatch > 0 || r.crcFieldMismatch > 0))
                || (o.fakeDirs && r.fakeDirs > 0)
                || (o.forgedEocd && r.forgedEocdAt0);
        if (o.packsquash && r.contentObfuscation) {
            r.verdictKey = "check.packsquash";
        } else if (protection) {
            r.verdictKey = "check.protected";
        } else if (!r.jvmReadable || r.unreadable > 0) {
            r.verdictKey = "check.damaged";
        } else if (o.crc && r.badCrc > 0) {
            r.verdictKey = "check.damaged";
        } else if (o.strict && (protection || r.badCrc > 0 || r.unreadable > 0 || r.contentObfuscation)) {
            r.verdictKey = "check.damaged";
        } else {
            r.verdictKey = "check.ok";
        }
        return r;
    }

    /** True for JSON content with no line breaks (i.e. minified). */
    private static boolean isMinifiedJson(byte[] d) {
        for (byte b : d) {
            if (b == '\n' || b == '\r') {
                return false;
            }
        }
        for (byte b : d) {
            if (b == '{' || b == '[') {
                return true;
            }
        }
        return false;
    }

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(s);
        }
        return sb.toString();
    }

    private static boolean isLocalSig(byte[] b, int off) {
        return b[off] == 'P' && b[off + 1] == 'K' && b[off + 2] == 3 && b[off + 3] == 4;
    }

    private static boolean isCdSig(byte[] b, int off) {
        return b[off] == 'P' && b[off + 1] == 'K' && b[off + 2] == 1 && b[off + 3] == 2;
    }

    /**
     * Locate a genuine end-of-central-directory record.
     *
     * Scans backwards for the PK\x05\x06 signature and accepts only candidates
     * whose recorded central directory offset and size fall inside the file
     * and (for non-empty archives) point at a central directory signature.
     * This rejects forged EOCD signatures or PK\x05\x06 bytes that happen to
     * appear inside compressed data.
     */
    private static int findValidEocd(byte[] b) {
        for (int i = b.length - 22; i >= 0; i--) {
            if (b[i] == 'P' && b[i + 1] == 'K' && b[i + 2] == 5 && b[i + 3] == 6) {
                long cdOff = u32(b, i + 16) & 0xffffffffL;
                long cdSize = u32(b, i + 12) & 0xffffffffL;
                if (cdOff > b.length || cdOff + cdSize > b.length) {
                    continue;
                }
                // A central-directory signature within a few bytes of cdOff
                // also counts: some protected archives carry a small prefix
                // right before the central directory, so the exact signature
                // is not always at cdOff.
                if (cdSize == 0 || cdSignatureNear(b, (int) cdOff)) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** True if a central-directory signature (PK\\x01\\x02) lies within ±8 bytes of cdOff. */
    private static boolean cdSignatureNear(byte[] b, int cdOff) {
        return cdSignaturePos(b, cdOff) >= 0;
    }

    /** Locate the central-directory signature within ±8 bytes of cdOff, or -1. */
    private static int cdSignaturePos(byte[] b, int cdOff) {
        if (cdOff < 0) {
            return -1;
        }
        int lo = Math.max(0, cdOff - 8);
        int hi = Math.min(b.length - 4, cdOff + 8);
        for (int j = lo; j <= hi; j++) {
            if (isCdSig(b, j)) {
                return j;
            }
        }
        return -1;
    }

    /** Locate a local-file-header signature (PK\\x03\\x04) within ±8 bytes of lho, or -1. */
    private static int localSigPos(byte[] b, long lho) {
        int off = (int) lho;
        if (off < 0) {
            return -1;
        }
        int lo = Math.max(0, off - 8);
        int hi = Math.min(b.length - 4, off + 8);
        for (int j = lo; j <= hi; j++) {
            if (isLocalSig(b, j)) {
                return j;
            }
        }
        return -1;
    }

    private static int u16(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8);
    }

    private static int u32(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8)
                | ((b[off + 2] & 0xff) << 16) | ((b[off + 3] & 0xff) << 24);
    }
}
