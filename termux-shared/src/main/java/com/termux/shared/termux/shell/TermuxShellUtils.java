package com.termux.shared.termux.shell;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.errors.Error;
import com.termux.shared.file.filesystem.FileTypes;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.file.FileUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;

import org.apache.commons.io.filefilter.TrueFileFilter;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TermuxShellUtils {

    private static final String LOG_TAG = "TermuxShellUtils";

    /**
     * Setup shell command arguments for the execute. The file interpreter may be prefixed to
     * command arguments if needed.
     */
    @NonNull
    public static String[] setupShellCommandArguments(@NonNull String executable, @Nullable String[] arguments) {
        // The file to execute may either be:
        // - An elf file
        // - A script file without shebang
        // - A file with shebang
        //
        // Additionally, on some devices/ROMs, /data/data may be mounted with the `noexec` flag.
        // In that case, direct execve() of $PREFIX/bin/* binaries fails with EACCES even if the
        // file mode is executable. We work around this by executing the program via the system
        // dynamic linker (e.g. /system/bin/linker64), which is located on an executable mount.

        String shebangInterpreter = null;

        try {
            File file = new File(executable);
            try (FileInputStream in = new FileInputStream(file)) {
                byte[] buffer = new byte[256];
                int bytesRead = in.read(buffer);
                if (bytesRead > 4) {
                    if (buffer[0] == 0x7F && buffer[1] == 'E' && buffer[2] == 'L' && buffer[3] == 'F') {
                        // ELF binary
                    } else if (buffer[0] == '#' && buffer[1] == '!') {
                        // Try to parse shebang.
                        StringBuilder builder = new StringBuilder();
                        for (int i = 2; i < bytesRead; i++) {
                            char c = (char) buffer[i];
                            if (c == ' ' || c == '\n') {
                                if (builder.length() == 0) {
                                    // Skip whitespace after shebang.
                                } else {
                                    // End of shebang.
                                    String shebangExecutable = builder.toString();
                                    if (shebangExecutable.startsWith("/usr") || shebangExecutable.startsWith("/bin")) {
                                        String[] parts = shebangExecutable.split("/");
                                        String binary = parts[parts.length - 1];
                                        shebangInterpreter = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/" + binary;
                                    } else {
                                        // Use the shebang executable as is.
                                        shebangInterpreter = shebangExecutable;
                                    }
                                    break;
                                }
                            } else {
                                builder.append(c);
                            }
                        }
                    } else {
                        // No shebang and no ELF, use standard shell.
                        shebangInterpreter = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/sh";
                    }
                }
            }
        } catch (IOException e) {
            // Ignore.
        }

        // Base executable + arguments.
        // - For scripts, base executable is interpreter and first arg is script path.
        // - For elf binaries, base executable is the binary itself.
        String baseExecutable;
        List<String> baseArguments = new ArrayList<>();
        if (shebangInterpreter != null) {
            baseExecutable = shebangInterpreter;
            baseArguments.add(executable);
        } else {
            baseExecutable = executable;
        }
        if (arguments != null) Collections.addAll(baseArguments, arguments);

        // Workaround for `noexec` mounts.
        final String linkerPath = getSystemLinkerPath();
        if (linkerPath != null && isPathOnNoexecMount(baseExecutable)) {
            List<String> wrapped = new ArrayList<>();
            wrapped.add(linkerPath);
            wrapped.add(baseExecutable);
            wrapped.addAll(baseArguments);
            return wrapped.toArray(new String[0]);
        }

        List<String> result = new ArrayList<>();
        result.add(baseExecutable);
        result.addAll(baseArguments);
        return result.toArray(new String[0]);
    }

    private static volatile Boolean sIsDataDataNoexec;

    /**
     * Returns {@code true} if the mount point containing {@code path} is mounted with `noexec`.
     */
    private static boolean isPathOnNoexecMount(@NonNull final String path) {
        // Cache a common case: if /data/data is mounted noexec then all Termux paths under it are affected.
        Boolean cached = sIsDataDataNoexec;
        if (cached != null && path.startsWith("/data/data/")) {
            return cached;
        }

        StringBuilder mountInfo = new StringBuilder();
        Error error = FileUtils.readTextFromFile("mountinfo", "/proc/self/mountinfo", null, mountInfo, true);
        if (error != null) {
            Logger.logVerbose(LOG_TAG, "Failed to read /proc/self/mountinfo: " + error);
            return false;
        }

        String bestMountPoint = null;
        boolean bestNoexec = false;

        String[] lines = mountInfo.toString().split("\\n");
        for (String line : lines) {
            // mountinfo format: id parent major:minor root mount_point options ... - fstype source super
            // We need mount_point (index 4) and options (index 5).
            String[] parts = line.split(" ");
            if (parts.length < 6) continue;
            String mountPoint = parts[4];
            String options = parts[5];

            if (!path.startsWith(mountPoint)) continue;
            // Ensure we match full path component boundaries.
            if (path.length() > mountPoint.length() && mountPoint.length() > 1 && path.charAt(mountPoint.length()) != '/') {
                continue;
            }

            if (bestMountPoint == null || mountPoint.length() > bestMountPoint.length()) {
                bestMountPoint = mountPoint;
                bestNoexec = options.contains("noexec");
            }
        }

        if (bestMountPoint != null && bestMountPoint.equals("/data/data")) {
            sIsDataDataNoexec = bestNoexec;
        }

        return bestNoexec;
    }

    /**
     * Get the system dynamic linker path that can execute binaries via interpreter mode.
     */
    @Nullable
    private static String getSystemLinkerPath() {
        // Most modern devices are 64-bit only, but keep a fallback.
        String[] candidates = new String[]{"/system/bin/linker64", "/system/bin/linker"};
        for (String candidate : candidates) {
            File f = new File(candidate);
            if (f.canExecute()) return candidate;
        }
        return null;
    }

    /** Clear files under {@link TermuxConstants#TERMUX_TMP_PREFIX_DIR_PATH}. */
    public static void clearTermuxTMPDIR(boolean onlyIfExists) {
        // Existence check before clearing may be required since clearDirectory() will automatically
        // re-create empty directory if doesn't exist, which should not be done for things like
        // termux-reset (d6eb5e35). Moreover, TMPDIR must be a directory and not a symlink, this can
        // also allow users who don't want TMPDIR to be cleared automatically on termux exit, since
        // it may remove files still being used by background processes (#1159).
        if(onlyIfExists && !FileUtils.directoryFileExists(TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH, false))
            return;

        Error error;

        TermuxAppSharedProperties properties = TermuxAppSharedProperties.getProperties();
        int days = properties.getDeleteTMPDIRFilesOlderThanXDaysOnExit();

        // Disable currently until FileUtils.deleteFilesOlderThanXDays() is fixed.
        if (days > 0)
            days = 0;

        if (days < 0) {
            Logger.logInfo(LOG_TAG, "Not clearing termux $TMPDIR");
        } else if (days == 0) {
            error = FileUtils.clearDirectory("$TMPDIR",
                FileUtils.getCanonicalPath(TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH, null));
            if (error != null) {
                Logger.logErrorExtended(LOG_TAG, "Failed to clear termux $TMPDIR\n" + error);
            }
        } else {
            error = FileUtils.deleteFilesOlderThanXDays("$TMPDIR",
                FileUtils.getCanonicalPath(TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH, null),
                TrueFileFilter.INSTANCE, days, true, FileTypes.FILE_TYPE_ANY_FLAGS);
            if (error != null) {
                Logger.logErrorExtended(LOG_TAG, "Failed to delete files from termux $TMPDIR older than " + days + " days\n" + error);
            }
        }
    }

}