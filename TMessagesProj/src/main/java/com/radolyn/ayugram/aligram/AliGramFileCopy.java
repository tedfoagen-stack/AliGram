package com.radolyn.ayugram.aligram;

import android.os.StatFs;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;

/**
 * The ONLY file-moving code on the AliGram preservation path
 * (Phase 2.2 §1.8, Phase 2.3 §2.2).
 *
 * <p>Never calls {@code AyuUtils.moveOrCopyFile} (renames first, then
 * deletes the original), {@code processAttachment},
 * {@code saveDownloadedMedia}, {@code AndroidUtilities.copyFile},
 * {@code File.renameTo} on a source (except the single temp→final publish
 * inside this helper), or {@code FileLoader.deleteFiles} on preserved
 * paths. Copy-only, always:
 *
 * <ol>
 *   <li>Pre-checks: source exists and is a file; {@code StatFs} headroom
 *       ≥ file size + margin; destination dir exists (created if needed).</li>
 *   <li>Copy to {@code <blobId>.<ext>.tmp-<refId>} in the SAME directory
 *       as the final blob (same filesystem, so the final rename is atomic).
 *       8 KiB single-pass stream with incremental SHA-256.</li>
 *   <li>{@code getFD().sync()} (fsync) before any rename.</li>
 *   <li>Verify: copied size == expected size AND SHA-256 is computed; on
 *       mismatch, delete the temp file, never bind.</li>
 *   <li>Atomic rename of the temp file to {@code <blobId>.<ext>}
 *       (≤3 rename retries); the Room transaction that binds {@code blobId}
 *       is the commit point.</li>
 * </ol>
 *
 * <p>Dedupe is the caller's job (it owns the Room transaction): after this
 * helper returns the SHA-256, the caller checks for an existing blob with
 * the same key and discards the temp copy when one exists.
 */
public final class AliGramFileCopy {

    private static final int BUFFER_SIZE = 8192;
    /** Headroom margin kept free beyond the file being copied. */
    private static final long HEADROOM_MARGIN_BYTES = 8L * 1024L * 1024L;

    private AliGramFileCopy() {
    }

    public static final class CopyResult {
        public final boolean ok;
        /** Final blob file (after the atomic rename), or null on failure. */
        public final File finalFile;
        /** Temp file left behind, if any (caller sweeps it). */
        public final File tempFile;
        public final String sha256Hex;
        public final long size;
        /** Machine reason on failure (see AliGramEligibility reasons). */
        public final String error;

        private CopyResult(boolean ok, File finalFile, File tempFile, String sha256Hex, long size, String error) {
            this.ok = ok;
            this.finalFile = finalFile;
            this.tempFile = tempFile;
            this.sha256Hex = sha256Hex;
            this.size = size;
            this.error = error;
        }
    }

    /** App-private blob root: {@code <no_backup>/aligram/[account<N>/]blobs/} (Phase 2.2 §6.5). */
    public static File getBlobDir(int accountId) {
        File base = ApplicationLoader.applicationContext.getNoBackupFilesDir();
        File dir = new File(base, "aligram");
        if (accountId != 0) {
            dir = new File(dir, "account" + accountId);
        }
        return new File(dir, "blobs");
    }

    /** Deterministic blob file name: {@code <blobId>.<ext>}. */
    public static File blobFileFor(int accountId, String blobId, String ext) {
        String name = ext == null || ext.isEmpty() ? blobId : blobId + "." + ext;
        return new File(getBlobDir(accountId), name);
    }

    /**
     * Copy-only verified copy. {@code expectedSize} is 0 when unknown (the
     * size check is then skipped; the SHA-256 is always computed).
     */
    public static CopyResult copyVerified(File source, int accountId, String finalFileName,
                                          long expectedSize, long refId) {
        if (source == null || !source.isFile()) {
            return new CopyResult(false, null, null, null, 0, AliGramEligibility.REASON_SOURCE_GONE);
        }
        long sourceSize = source.length();
        if (expectedSize > 0 && sourceSize != expectedSize) {
            return new CopyResult(false, null, null, null, sourceSize, AliGramEligibility.REASON_SIZE_MISMATCH);
        }
        File dir = getBlobDir(accountId);
        if (!dir.exists() && !dir.mkdirs()) {
            FileLog.e("AliGram: cannot create blob dir " + dir.getAbsolutePath());
            return new CopyResult(false, null, null, null, 0, AliGramEligibility.REASON_NO_SPACE);
        }
        try {
            StatFs stat = new StatFs(dir.getAbsolutePath());
            long free = stat.getAvailableBytes();
            if (free < sourceSize + HEADROOM_MARGIN_BYTES) {
                return new CopyResult(false, null, null, null, 0, AliGramEligibility.REASON_NO_SPACE);
            }
        } catch (Throwable e) {
            // O-03 (Phase 3.2): the fail-safe itself is unchanged — the copy
            // still proceeds — but the log must say so explicitly: there is
            // now NO headroom check, and the mid-copy ENOSPC handling is
            // the only remaining guard. Context (blob dir, source file,
            // failure cause) is included so the line is actionable.
            FileLog.e("AliGram: StatFs failed for blob dir " + dir.getAbsolutePath() + " while copying " + source.getAbsolutePath() + " (" + e + ") — continuing UNGUARDED with no headroom check; the mid-copy ENOSPC handling is the only remaining guard", e);
        }

        File tempFile = new File(dir, finalFileName + ".tmp-" + refId);
        File finalFile = new File(dir, finalFileName);
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            return new CopyResult(false, null, tempFile, null, 0, AliGramEligibility.REASON_DOWNLOAD_FAILED);
        }
        long copied = 0;
        try (InputStream in = new FileInputStream(source);
             FileOutputStream out = new FileOutputStream(tempFile)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                digest.update(buffer, 0, read);
                copied += read;
                if (copied > sourceSize) {
                    // Source grew mid-copy: never bind a moving target.
                    out.close();
                    deleteQuietly(tempFile);
                    return new CopyResult(false, null, null, null, copied, AliGramEligibility.REASON_SIZE_MISMATCH);
                }
            }
            out.getFD().sync();
        } catch (IOException e) {
            FileLog.e("AliGram: copy failed for " + source.getAbsolutePath(), e);
            deleteQuietly(tempFile);
            String reason = e.getMessage() != null && e.getMessage().contains("No space")
                    ? AliGramEligibility.REASON_NO_SPACE : AliGramEligibility.REASON_DOWNLOAD_FAILED;
            return new CopyResult(false, null, null, null, copied, reason);
        }
        // Source truncated mid-copy (or the stat lied): size verification.
        if (source.length() != copied || (expectedSize > 0 && copied != expectedSize)) {
            deleteQuietly(tempFile);
            return new CopyResult(false, null, null, null, copied, AliGramEligibility.REASON_SIZE_MISMATCH);
        }
        String sha256Hex = toHex(digest.digest());
        boolean renamed = false;
        for (int attempt = 0; attempt < 3 && !renamed; attempt++) {
            if (finalFile.exists()) {
                // Phase 3.1 (D-09): a stale pending-<refId>.blob from a
                // previous crashed/interrupted copy for the same ref must
                // NEVER be accepted unverified — it may hold different
                // source bytes than the stream just copied, and the
                // caller would publish it under a blobId computed from
                // the CURRENT stream's SHA (blob content != stored SHA).
                // The pending name is unique per refId and copyQueue is
                // single-threaded, so an existing file here is always
                // stale: delete it and retry the rename.
                deleteQuietly(finalFile);
            }
            renamed = tempFile.renameTo(finalFile);
        }
        if (!renamed) {
            deleteQuietly(tempFile);
            return new CopyResult(false, null, null, sha256Hex, copied, AliGramEligibility.REASON_NO_SPACE);
        }
        return new CopyResult(true, finalFile, null, sha256Hex, copied, null);
    }

    /** SHA-256 hex of an existing file (watchdog reconciliation / verification). */
    public static String computeSha256(File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new FileInputStream(file)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return toHex(digest.digest());
        } catch (Exception e) {
            FileLog.e("AliGram: sha256 failed for " + file.getAbsolutePath(), e);
            return null;
        }
    }

    public static String toHex(byte[] hash) {
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public static void deleteQuietly(File file) {
        if (file == null) {
            return;
        }
        try {
            if (!file.delete()) {
                file.deleteOnExit();
            }
        } catch (Throwable ignored) {
        }
    }
}
