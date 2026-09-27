package com.nitroboost.app.shizuku;

interface INitroService {
    // Reserved: destroy method defined by the Shizuku server
    void destroy() = 16777114;

    // Reserved: exit method defined by the user
    void exit() = 1;

    // Run a shell command as the Shizuku shell user.
    // Returns: exitCode + '\u0000' + base64(stdout) + '\u0000' + base64(stderr)
    String runShell(String cmd) = 2;

    // Read a /sys or /proc file. Returns the trimmed content, or "" when unreadable.
    String readSys(String path) = 3;
}
