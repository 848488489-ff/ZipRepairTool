package com.github._848488489_ff.mcpack_deobfuscator;

/**
 * Which integrity checks to run (all enabled by default). Mirrors the optional
 * selectable checks / transformers idea, but needs no configuration file:
 * construct {@link #defaults()} or populate explicitly.
 */
public final class CheckOptions {

    /** Local/central metadata divergence: name, size, CRC field mismatch. */
    public boolean metadata = true;
    /** Data CRC-32 verification. */
    public boolean crc = true;
    /** Fake directory entries (real files disguised as directories). */
    public boolean fakeDirs = true;
    /** Forged EOCD signature at offset 0. */
    public boolean forgedEocd = true;
    /** PackSquash content obfuscation detection. */
    public boolean packsquash = true;
    /** PNG content-obfuscation scan (PackSquash). */
    public boolean png = true;
    /** Ogg content-obfuscation scan (PackSquash). */
    public boolean ogg = true;
    /** JSON-minified scan (PackSquash). */
    public boolean json = true;
    /** Fail (damaged verdict) on any finding, not just structural damage. */
    public boolean strict = false;

    /** All checks enabled, non-strict. */
    public static CheckOptions defaults() {
        return new CheckOptions();
    }
}
