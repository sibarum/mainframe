package dev.mainframe.pty;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs(OS.WINDOWS)
class ConPtyTest {

    @Test
    void powershellEchoesThroughTheConsole() throws Exception {
        try (ConPty pty = ConPty.start("powershell.exe -NoLogo -NoProfile", 100, 30, null)) {
            StringBuilder out = new StringBuilder();
            Thread reader = new Thread(() -> {
                byte[] b = new byte[8192];
                int n;
                while ((n = pty.read(b)) > 0) {
                    synchronized (out) { out.append(new String(b, 0, n, StandardCharsets.UTF_8)); }
                }
            });
            reader.setDaemon(true);
            reader.start();
            Thread.sleep(1500);
            pty.write("Write-Output ('mainframe-' + (6*7))\r");
            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline) {
                synchronized (out) { if (out.toString().contains("mainframe-42")) break; }
                Thread.sleep(100);
            }
            synchronized (out) { assertTrue(out.toString().contains("mainframe-42"), out.toString()); }
        }
    }
}
