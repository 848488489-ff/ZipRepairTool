package com.ziptool;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Core repair engine.
 *
 * Strategy: open the archive with java.util.zip.ZipFile (random access) and
 * rebuild a clean ZIP through ZipOutputStream. ZipFile locates every entry via
 * the central directory and reads the raw bytes at the recorded local header
 * offset, without validating that the local header metadata (file name, sizes,
 * general-purpose flags, external attributes) agrees with the central
 * directory. Many damaged archives exploit
 * exactly this gap: they corrupt local header fields (file names, sizes,
 * fake directory markers, forged EOCD signatures) so that strict validators
 * such as 7-Zip, WinRAR, Windows Explorer or python's zipfile reject the file,
 * while JVM-based applications still read it without issue.
 *
 * Re-emitting every entry through ZipOutputStream regenerates all metadata
 * from scratch, producing an archive that any extractor can open.
 */
public final class RepairEngine {

    private RepairEngine() {
    }

    /** Outcome of a repair run. */
    public static final class Result {
        /** Entries successfully copied. */
        public int ok = 0;
        /** Entries that could not be read (recorded in warnings). */
        public int skipped = 0;
        /** Entries marked as directories but carrying file data. */
        public int fakeDirs = 0;
        /** Total decompressed bytes written. */
        public long bytes = 0;
        /** PNG files whose corrupted chunk CRC / Adler-32 was repaired. */
        public int pngFixed = 0;
        /** Ogg files whose corrupted headers were repaired. */
        public int oggFixed = 0;
        /** JSON files re-formatted (prettified). */
        public int jsonPrettified = 0;
        /** NBT files structurally validated. */
        public int nbtChecked = 0;
        /** NBT files that failed structural validation. */
        public int nbtBad = 0;
        /** Per-entry problems encountered during repair. */
        public final List<String> warnings = new ArrayList<String>();
    }

    /** Progress callback, invoked from the calling (worker) thread. */
    public interface Progress {
        /**
         * @param done  number of entries processed so far
         * @param total total number of entries
         */
        void update(int done, int total);
    }

    /**
     * Detect structural anomalies without modifying anything.
     *
     * @param input the archive to inspect
     * @return a list of human-readable findings (empty if none found)
     * @throws IOException if the file cannot be read
     */
    public static List<String> detect(Path input) throws IOException {
        List<String> findings = new ArrayList<String>();

        byte[] head = new byte[4];
        try (InputStream in = Files.newInputStream(input)) {
            if (in.read(head) != 4) {
                throw new IOException("File is too small: " + input);
            }
        }
        // A forged EOCD signature planted at offset 0 tricks stream scanners
        // that hunt for the end-of-central-directory record from the start.
        if (head[0] == 'P' && head[1] == 'K' && head[2] == 5 && head[3] == 6) {
            findings.add("Forged EOCD signature PK\\x05\\x06 at file offset 0");
        }

        try (ZipFile zf = new ZipFile(input.toFile())) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                // Fake directory: name ends with '/' or DOS dir attribute set,
                // yet the entry points at real file data.
                if (e.isDirectory()) {
                    findings.add("Fake directory entry: " + e.getName());
                }
            }
        }
        return findings;
    }

    /**
     * Rebuild a clean ZIP archive from the input.
     *
     * @param input    the corrupted archive
     * @param output   where to write the repaired archive (overwritten)
     * @param progress optional progress callback, may be null
     * @return the repair result
     * @throws IOException if the archive cannot be opened or written
     */
    public static Result repair(Path input, Path output, Progress progress) throws IOException {
        Result res = new Result();

        try (ZipFile zf = new ZipFile(input.toFile());
             ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(output))) {

            Enumeration<? extends ZipEntry> en = zf.entries();
            List<ZipEntry> entries = new ArrayList<ZipEntry>();
            while (en.hasMoreElements()) {
                entries.add(en.nextElement());
            }
            int total = entries.size();
            int done = 0;

            for (ZipEntry e : entries) {
                String name = e.getName();

                // Fake directory entries carry real data; strip the trailing
                // slash and read them as ordinary files.
                if (e.isDirectory()) {
                    res.fakeDirs++;
                    if (name.endsWith("/")) {
                        name = name.substring(0, name.length() - 1);
                    }
                    if (name.isEmpty()) {
                        done++;
                        continue;
                    }
                }

                try {
                    ZipEntry out = new ZipEntry(name);
                    out.setMethod(ZipEntry.DEFLATED);
                    // Intentionally do NOT copy time / extra / comment /
                    // external attributes: let ZipOutputStream regenerate a
                    // fully consistent set of metadata.
                    zos.putNextEntry(out);
                    try (InputStream in = zf.getInputStream(e)) {
                        writeRepaired(in, zos, name, res);
                    }
                    zos.closeEntry();
                    res.ok++;
                } catch (Exception ex) {
                    res.skipped++;
                    res.warnings.add(name + ": " + ex.getMessage());
                }

                done++;
                if (progress != null) {
                    progress.update(done, total);
                }
            }
        }
        return res;
    }

    /**
     * Stream an entry to the output archive, applying content-level repair to
     * known content-corrupted file types (PNG, Ogg Vorbis, JSON, NBT).
     *
     * <p>Only the listed types are buffered and repaired; every other entry is
     * streamed through unchanged, so repair stays fast on large archives.
     */
    private static void writeRepaired(InputStream in, ZipOutputStream zos,
            String name, Result res) throws IOException {
        String lower = name.toLowerCase(Locale.ROOT);

        if (lower.endsWith(".png")) {
            byte[] data = readAll(in);
            byte[] fixed = ContentRepairer.fixPng(data);
            if (fixed != data) {
                res.pngFixed++;
            }
            zos.write(fixed);
            res.bytes += fixed.length;
            return;
        }
        if (lower.endsWith(".ogg") || lower.endsWith(".oga")) {
            byte[] data = readAll(in);
            byte[] fixed = ContentRepairer.fixOgg(data);
            if (fixed != data) {
                res.oggFixed++;
            }
            zos.write(fixed);
            res.bytes += fixed.length;
            return;
        }
        if (lower.endsWith(".json") || lower.endsWith(".mcmeta")
                || lower.endsWith(".jsonc") || lower.endsWith(".mcmetac")
                || lower.endsWith(".jem") || lower.endsWith(".jpm")) {
            byte[] data = readAll(in);
            // Only re-format content that is actually minified (no line breaks):
            // re-indenting an already pretty file would double the indentation.
            if (!containsLineBreak(data)) {
                byte[] pretty = ContentRepairer.prettifyJson(data);
                if (pretty != null && !java.util.Arrays.equals(pretty, data)) {
                    res.jsonPrettified++;
                    zos.write(pretty);
                    res.bytes += pretty.length;
                    return;
                }
            }
            zos.write(data);
            res.bytes += data.length;
            return;
        }
        if (lower.endsWith(".nbt")) {
            byte[] data = readAll(in);
            String err = ContentRepairer.checkNbt(data);
            res.nbtChecked++;
            if (err != null) {
                res.nbtBad++;
                res.warnings.add(name + ": NBT " + err);
            }
            zos.write(data);
            res.bytes += data.length;
            return;
        }

        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) != -1) {
            zos.write(buf, 0, n);
            res.bytes += n;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private static boolean containsLineBreak(byte[] data) {
        for (byte b : data) {
            if (b == '\n' || b == '\r') {
                return true;
            }
        }
        return false;
    }
}
