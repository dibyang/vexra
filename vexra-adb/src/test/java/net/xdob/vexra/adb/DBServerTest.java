package net.xdob.vexra.adb;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.net.Socket;
import org.junit.jupiter.api.Test;

class DBServerTest {

    @Test
    void startsAndStopsH2TcpServer() throws Exception {
        int port = findFreePort();
        try {
            DBServer.adb.start(port);
            assertRunningEventually();
            assertSocketAccepts(port);
        } finally {
            DBServer.adb.stop();
        }
        assertFalse(DBServer.adb.isRunning());
    }

    private static void assertRunningEventually() throws InterruptedException {
        for (int i = 0; i < 20; i++) {
            if (DBServer.adb.isRunning()) {
                return;
            }
            Thread.sleep(100L);
        }
        assertTrue(DBServer.adb.isRunning());
    }

    private static int findFreePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void assertSocketAccepts(int port) throws Exception {
        try (Socket ignored = new Socket("127.0.0.1", port)) {
            assertTrue(ignored.isConnected());
        }
    }
}
