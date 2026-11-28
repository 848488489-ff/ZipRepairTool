# mcpack-deobfuscator

<p align="center">
  <a href="README.md"><img src="https://img.shields.io/badge/lang-English-blue.svg" alt="English"></a>
  <a href="README_zh_cn.md"><img src="https://img.shields.io/badge/lang-%E7%AE%80%E4%BD%93%E4%B8%AD%E6%96%87-orange.svg" alt="简体中文"></a>
</p>

<p align="center">
  <a href="version.json"><img src="https://img.shields.io/badge/version-1.1-blue.svg" alt="Version 1.1"></a>
  <img src="https://img.shields.io/badge/license-Unlicense-lightgrey.svg" alt="License: Unlicense">
  <img src="https://img.shields.io/badge/JDK-8+-orange.svg" alt="JDK 8+">
  <img src="https://img.shields.io/badge/build-passing-brightgreen.svg" alt="Build passing">
  <img src="https://img.shields.io/badge/platform-Windows%20%7C%20Linux%20%7C%20macOS-informational.svg" alt="Cross-platform">
  <img src="https://img.shields.io/badge/release-JAR%20%2B%20docs-blueviolet.svg" alt="Release: JAR + docs">
</p>

<p align="center">
  <img src="https://img.shields.io/github/last-commit/848488489-ff/mcpack-deobfuscator.svg" alt="Last commit">
  <img src="https://img.shields.io/github/languages/top/848488489-ff/mcpack-deobfuscator.svg" alt="Top language">
  <img src="https://img.shields.io/github/repo-size/848488489-ff/mcpack-deobfuscator.svg" alt="Repo size">
  <img src="https://img.shields.io/github/release/848488489-ff/mcpack-deobfuscator.svg" alt="Latest release">
  <img src="https://img.shields.io/github/forks/848488489-ff/mcpack-deobfuscator.svg" alt="Forks">
  <img src="https://img.shields.io/github/stars/848488489-ff/mcpack-deobfuscator.svg" alt="Stars">
</p>

mcpack-deobfuscator is a **Java** command-line and GUI tool that deobfuscates
**Minecraft resource packs (mcpack)** that have been protected or obfuscated by
tools such as **PackSquash**. Such packs cannot be opened by strict extractors
(7-Zip, WinRAR, Windows Explorer, Python's `zipfile`) even though the game
itself reads them fine. This tool detects the obfuscation, rebuilds a clean
archive, and repairs the obfuscated file contents so the extracted files
actually open again.

It also offers an integrity **check** and a tunable **obfuscate** mode (the
inverse of repair) for producing such packs deliberately.

## Why these packs fail

Protected packs are not actually encrypted. They are damaged in a specific
way: the **central directory** and the **local file header** metadata disagree.
Common corruptions include:

- File names in the local header rewritten (uppercased, or even replaced with
  a different name) so they no longer match the central directory.
- Compressed / uncompressed sizes or CRC-32 values modified.
- Real files flagged as directories (fake directory entries).
- A forged end-of-central-directory (EOCD) signature planted at offset 0.

Strict validators — 7-Zip, WinRAR, Windows Explorer, Python's `zipfile` —
compare the two metadata blocks and reject the archive ("file name in
directory and header differ", "bad CRC-32", ...). The JVM's
`java.util.zip.ZipFile` does not perform that consistency check: it locates
entries through the central directory and reads the raw bytes directly, which
is why the pack still works in Minecraft.

Some obfuscators go further and also corrupt the *contents* of stored files so
that strict extractors additionally fail on the extracted data:

- **PNG obfuscation**: a random key is XORed into every chunk CRC-32 and into
  the Adler-32 at the tail of the IDAT zlib stream. The chunk data itself is
  untouched, so repair is possible without knowing the key: inflate the IDAT
  stream, recompute the Adler-32 and recompute every chunk CRC-32.
- **Ogg obfuscation**: the first page stream serial is rewritten (+1), every
  granule position is mapped to `i64::MIN + granule`, and the Vorbis
  identification bitrate fields are forced to -1. Repair restores the serial,
  un-maps the granules and rewrites the Ogg page CRC-32.
- **JSON minification**: whitespace is stripped. Repair re-formats (prettifies)
  the file so it is readable and editable again.

## Features

- **Check**: full inspection — entry count, local/central metadata divergence
  (names, sizes, CRC), fake directory entries, forged EOCD signature, data
  CRC-32 verification and unreadable-entry detection. It also scans the stored
  PNG / Ogg / JSON files for PackSquash content-obfuscation features and
  issues a dedicated **PACKSQUASH** verdict: corrupt PNG chunk CRC-32, Ogg
  headers with rewritten serials / mapped granules / forced bitrates, and the
  characteristic obfuscation CRC fingerprint (local and central CRC fields
  agree with each other but disagree with the real data, because one key was
  XORed into both).
- **Repair**: rebuild a clean ZIP. Data is read with `ZipFile` (random access)
  and re-emitted through `ZipOutputStream`, which regenerates fully consistent
  metadata. The output opens in any extractor. Stored `.png`, `.ogg`/`.oga`,
  `.json`/`.mcmeta`/`.jsonc` and `.nbt` entries are also repaired / validated
  as described above, so extracted files open correctly.
- **Obfuscate**: the inverse of repair. Tunable parameters: name rewrite mode
  (`none` / `upper` / `digit` / `random`), percentage of entries affected,
  fake directory disguises, whitelist (untouched entries), forged EOCD prefix,
  central-directory size perturbation, CRC-32 perturbation, RNG seed, and
  **content obfuscation** of the stored PNG / Ogg / JSON files. Data
  descriptors are stripped so the local header carries real sizes, which is
  what makes strict extractors reject the archive while the JVM (which trusts
  the central directory) keeps reading it. Content obfuscation can be enabled
  **per type** (PNG / Ogg / JSON independently), e.g. obfuscate only Ogg
  (`--content-ogg`) so images stay fine while sounds break.

## Usage

GUI (three tabs: Check / Repair / Obfuscate; drag & drop a ZIP onto a tab, or
pick one with the file chooser):

    java -jar mcpack-deobfuscator.jar

Command line:

    java -jar mcpack-deobfuscator.jar -check <in.zip>
    java -jar mcpack-deobfuscator.jar -repair <in.zip> <out.zip>
    java -jar mcpack-deobfuscator.jar -obfuscate <in.zip> <out.zip>
        [--name <none|upper|digit|random>] [--percent <0-100>]
        [--fake-dir <0-100>] [--eocd] [--crc] [--no-sizes]
        [--content|--content-png|--content-ogg|--content-json]
        [--seed <n>] [--whitelist <a,b,c>]

`--seed` accepts decimal or `0x`-prefixed hexadecimal and covers the full
64-bit unsigned range (0 .. 18446744073709551615), stored as the
corresponding bit pattern. Two obfuscation runs with the same seed and
parameters produce the same perturbation plan.

### Whitelist (untouched entries)

Whitelisted entries are **completely skipped** during obfuscation: no name
rewrite, no size perturbation, no fake-directory disguise, no content
obfuscation.

- **Format**: each entry is an **exact entry name** or a **path prefix** from
  the archive. In the GUI, add them one by one with the list (input field +
  Add/Remove buttons); on the command line, separate them with commas,
  `--whitelist <a,b,c>`.
- **Matching**: exact entry-name match, or a path prefix that matches every
  entry below it. For example `assets/minecraft/textures/item/diamond_sword.png`
  protects that file (and its `.mcmeta`), while `assets/minecraft/textures`
  protects the whole directory.
- **Missing check**: a whitelist entry that does **not exist** in the archive
  (no exact or prefix match) aborts obfuscation with an error listing the
  missing entries — so you do not silently assume a file is protected when a
  typo left it unprotected.
- **Example** (protect an image texture, obfuscate everything else's PNG):

    java -jar mcpack-deobfuscator.jar -obfuscate pack.zip out.zip \
        --content-png --whitelist assets/minecraft/textures/item/diamond_sword.png

The UI language follows the system locale (Chinese / English) and can be
changed from the window; the command-line output is English.

### Detecting and repairing obfuscated packs

PackSquash obfuscated packs are recognised automatically and reported
with the dedicated `check.packsquash` verdict plus a per-feature summary:

    $ java -jar mcpack-deobfuscator.jar -check "pack.zip"
    verdict: check.packsquash
    entries=909 nameMismatch=908 sizeMismatch=908 crcFieldMismatch=0 fakeDirs=2 forgedEocd=true badCrc=906 unreadable=0 jvmReadable=true
    obfuscated=true png=675 ogg=6 json=124 crcFingerprint=906
      675 PNG files with corrupt chunk CRC-32 (e.g. assets/minecraft/textures/block/stone.png)
      6 Ogg files with obfuscated headers
      906 entries with a PackSquash CRC fingerprint (local==central CRC, both differ from data)
      124 JSON files appear minified

Repairing then rebuilds the ZIP container and fixes the obfuscated file
contents, so the extracted files actually open:

    $ java -jar mcpack-deobfuscator.jar -repair "pack.zip" out.zip
    ok=909 skipped=0 fakeDirs=2 bytes=6272438
    content: pngFixed=676 oggFixed=6 jsonPrettified=125 nbtChecked=0 nbtBad=0

After repair, a re-check reports `check.ok` with every obfuscation counter at
zero.

## Build

Requires JDK 8 or newer.

- Windows: `build.bat`
- Linux / macOS: `./build.sh`

## License

[Unlicense](LICENSE) — this software is released into the public domain.
