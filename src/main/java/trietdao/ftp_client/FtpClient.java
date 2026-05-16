package trietdao.ftp_client;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

public class FtpClient {
    // Callback used by the UI to stream protocol traffic into the console.
    public interface LogListener {
        void onLog(String message);
    }

    private Socket controlSocket;
    private BufferedReader reader;
    private BufferedWriter writer;
    private final StringBuilder protocolLog = new StringBuilder();
    private LogListener logListener;

    // Registers a UI listener that receives each command/reply line.
    public void setLogListener(LogListener logListener) {
        this.logListener = logListener;
    }

    public void connect(String host, int port) throws IOException {
        // Control socket carries FTP commands/replies.
        controlSocket = new Socket(host, port);

        reader = new BufferedReader(
            new InputStreamReader(controlSocket.getInputStream(), "US-ASCII")
        );

        writer = new BufferedWriter(
            new OutputStreamWriter(controlSocket.getOutputStream(), "US-ASCII")
        );

        FtpReply welcome = readReply();
        System.out.println(welcome);

        if (welcome.code != FtpReplyCodes.SERVICE_READY) {
            throw new IOException("Server is not ready: " + welcome.message);
        }
    }

    public String drainProtocolLog() {
        // Used by UI code that wants to clear buffered protocol output.
        String log = protocolLog.toString();
        protocolLog.setLength(0);
        return log;
    }

    private void emitLog(String message) {
        // Keep both a local buffer and a live callback stream.
        protocolLog.append(message).append(System.lineSeparator());
        if (logListener != null) {
            logListener.onLog(message);
        }
    }

    public void login(String username, String password) throws IOException {
        // Standard FTP auth flow: USER -> PASS.
        sendCommand("USER " + username);
        FtpReply userReply = readReply();
        System.out.println(userReply);

        if (userReply.code == FtpReplyCodes.USER_LOGGED_IN) {
            // Some servers log in directly after USER (no PASS required).
            return;
        }

        if (userReply.code != FtpReplyCodes.NEED_PASSWORD) {
            throw new IOException("Login failed: " + userReply.message);
        }

        sendCommand("PASS " + password);
        FtpReply passReply = readReply();
        System.out.println(passReply);

        if (passReply.code != FtpReplyCodes.USER_LOGGED_IN) {
            throw new IOException("Login failed: " + passReply.message);
        }
    }

    public void setBinaryMode() throws IOException {
        // Binary mode is required for reliable file transfer.
        FtpReply reply = sendCommandAndRead("TYPE I");
        System.out.println(reply);

        if (reply.code != FtpReplyCodes.COMMAND_OK) {
            throw new IOException("Could not switch to binary mode.");
        }
    }

    public void setAsciiMode() throws IOException {
        // ASCII mode is mainly useful for line-oriented text transfers.
        FtpReply reply = sendCommandAndRead("TYPE A");
        System.out.println(reply);

        if (reply.code != FtpReplyCodes.COMMAND_OK) {
            throw new IOException("Could not switch to ASCII mode.");
        }
    }

    public String printWorkingDirectory() throws IOException {
        // PWD typically returns the path in quotes, e.g. 257 "/home".
        FtpReply reply = sendCommandAndRead("PWD");
        System.out.println(reply);

        if (reply.code != FtpReplyCodes.PATH_CREATED) {
            throw new IOException("PWD failed: " + reply.message);
        }

        String message = reply.message;
        int firstQuote = message.indexOf('"');
        int secondQuote = message.indexOf('"', firstQuote + 1);

        if (firstQuote >= 0 && secondQuote > firstQuote) {
            return message.substring(firstQuote + 1, secondQuote);
        }

        // Fallback for non-standard server messages.
        return "/";
    }

    public void changeDirectory(String remotePath) throws IOException {
        // Change current server directory.
        FtpReply reply = sendCommandAndRead("CWD " + remotePath);
        System.out.println(reply);

        if (reply.code != FtpReplyCodes.ACTION_COMPLETED) {
            throw new IOException("CWD failed: " + reply.message);
        }
    }

    public void createDirectory(String remotePath) throws IOException {
        // Create remote directory.
        FtpReply reply = sendCommandAndRead("MKD " + remotePath);
        System.out.println(reply);

        if (reply.code != FtpReplyCodes.PATH_CREATED) {
            throw new IOException("MKD failed: " + reply.message);
        }
    }

    public void deleteFile(String remotePath) throws IOException {
        // Delete a remote file.
        FtpReply reply = sendCommandAndRead("DELE " + remotePath);
        System.out.println(reply);

        if (reply.code != FtpReplyCodes.ACTION_COMPLETED) {
            throw new IOException("DELE failed: " + reply.message);
        }
    }

    public void removeDirectory(String remotePath) throws IOException {
        // Remove a remote directory (server usually requires it to be empty).
        FtpReply reply = sendCommandAndRead("RMD " + remotePath);
        System.out.println(reply);

        if (reply.code != FtpReplyCodes.ACTION_COMPLETED) {
            throw new IOException("RMD failed: " + reply.message);
        }
    }

    public List<String> list(String remotePath) throws IOException {
        // Data transfers (LIST/RETR/STOR) use a separate PASV socket.
        try (Socket dataSocket = openPassiveDataConnection()) {
            String command = remotePath == null || remotePath.isBlank() ? "LIST" : "LIST " + remotePath;
            FtpReply listReply = sendCommandAndRead(command);
            System.out.println(listReply);

            if (listReply.code != FtpReplyCodes.OPENING_DATA_CONNECTION
                    && listReply.code != FtpReplyCodes.DATA_ALREADY_OPEN) {
                throw new IOException("LIST failed: " + listReply.message);
            }

            List<String> lines = new ArrayList<>();
                try (BufferedReader dataReader = new BufferedReader(
                    new InputStreamReader(dataSocket.getInputStream(), "US-ASCII"))) {
                String line;
                // Read raw LIST output line-by-line.
                while ((line = dataReader.readLine()) != null) {
                    lines.add(line);
                }
            }

            FtpReply finalReply = readReply();
            System.out.println(finalReply);

            if (finalReply.code != FtpReplyCodes.TRANSFER_COMPLETE
                    && finalReply.code != FtpReplyCodes.ACTION_COMPLETED) {
                throw new IOException("LIST may have failed: " + finalReply.message);
            }

            return lines;
        }
    }

    public void uploadFile(String localPath, String remoteFileName) throws IOException {
        // Upload uses STOR over a passive data socket.
        setBinaryMode();

        try (Socket dataSocket = openPassiveDataConnection();
             FileInputStream fileInput = new FileInputStream(localPath);
             OutputStream dataOut = dataSocket.getOutputStream()) {

            sendCommand("STOR " + remoteFileName);
            FtpReply storReply = readReply();
            System.out.println(storReply);

            if (storReply.code != FtpReplyCodes.OPENING_DATA_CONNECTION
                    && storReply.code != FtpReplyCodes.DATA_ALREADY_OPEN) {
                throw new IOException("Server refused STOR: " + storReply.message);
            }

            byte[] buffer = new byte[4096];
            int bytesRead;

            // Stream local file bytes to server.
            while ((bytesRead = fileInput.read(buffer)) != -1) {
                dataOut.write(buffer, 0, bytesRead);
            }

            dataOut.flush();
        }

        FtpReply finalReply = readReply();
        System.out.println(finalReply);

        if (finalReply.code != FtpReplyCodes.TRANSFER_COMPLETE
                && finalReply.code != FtpReplyCodes.ACTION_COMPLETED) {
            throw new IOException("Upload may have failed: " + finalReply.message);
        }
    }

    public void downloadFile(String remoteFileName, String localPath) throws IOException {
        // Download uses RETR over a passive data socket.
        setBinaryMode();

        try (Socket dataSocket = openPassiveDataConnection();
             OutputStream fileOut = new FileOutputStream(localPath)) {

            FtpReply retrReply = sendCommandAndRead("RETR " + remoteFileName);
            System.out.println(retrReply);

            if (retrReply.code != FtpReplyCodes.OPENING_DATA_CONNECTION
                    && retrReply.code != FtpReplyCodes.DATA_ALREADY_OPEN) {
                throw new IOException("RETR failed: " + retrReply.message);
            }

            byte[] buffer = new byte[4096];
            int bytesRead;

            // Stream server bytes into local file.
            while ((bytesRead = dataSocket.getInputStream().read(buffer)) != -1) {
                fileOut.write(buffer, 0, bytesRead);
            }

            fileOut.flush();
        }

        FtpReply finalReply = readReply();
        System.out.println(finalReply);

        if (finalReply.code != FtpReplyCodes.TRANSFER_COMPLETE
                && finalReply.code != FtpReplyCodes.ACTION_COMPLETED) {
            throw new IOException("Download may have failed: " + finalReply.message);
        }
    }

    private Socket openPassiveDataConnection() throws IOException {
        // Ask server for passive endpoint used by LIST/RETR/STOR.
        FtpReply reply = sendCommandAndRead("PASV");
        System.out.println(reply);

        if (reply.code != FtpReplyCodes.ENTERING_PASSIVE_MODE) {
            throw new IOException("PASV failed: " + reply.message);
        }

        String message = reply.message;

        int start = message.indexOf('(');
        int end = message.indexOf(')', start);

        if (start == -1 || end == -1) {
            throw new IOException("Invalid PASV response: " + message);
        }

        String[] parts = message.substring(start + 1, end).split(",");

        if (parts.length != 6) {
            throw new IOException("Invalid PASV address format: " + message);
        }

        String ip = parts[0] + "." + parts[1] + "." + parts[2] + "." + parts[3];

        int p1 = Integer.parseInt(parts[4]);
        int p2 = Integer.parseInt(parts[5]);
        int port = p1 * 256 + p2;

        Socket dataSocket = new Socket();
        try {
            dataSocket.connect(new InetSocketAddress(ip, port), 5000);
            return dataSocket;
        } catch (IOException firstFailure) {
            // Some servers return an unroutable PASV IP; fall back to control host.
            dataSocket.close();

            Socket fallbackSocket = new Socket();
            String controlHost = controlSocket.getInetAddress().getHostAddress();
            fallbackSocket.connect(new InetSocketAddress(controlHost, port), 5000);
            return fallbackSocket;
        }
    }

    public void quit() throws IOException {
        // Try graceful session termination before closing sockets.
        FtpReply reply = sendCommandAndRead("QUIT");
        System.out.println(reply);
        close();
    }

    public void close() throws IOException {
        // Close in reader/writer/socket order and null out references.
        if (reader != null) {
            reader.close();
            reader = null;
        }
        if (writer != null) {
            writer.close();
            writer = null;
        }
        if (controlSocket != null) {
            controlSocket.close();
            controlSocket = null;
        }
    }

    private void sendCommand(String command) throws IOException {
        // Do not leak plaintext passwords in the UI/terminal log.
        String logLine;
        if (command.startsWith("PASS ")) {
            logLine = ">> PASS ********";
        } else {
            logLine = ">> " + command;
        }

        emitLog(logLine);
        System.out.println(logLine);

        writer.write(command + "\r\n");
        writer.flush();
    }

    private FtpReply sendCommandAndRead(String command) throws IOException {
        // Utility for one-request/one-reply commands.
        sendCommand(command);
        return readReply();
    }

    private FtpReply readReply() throws IOException {
        String firstLine = reader.readLine();

        if (firstLine == null) {
            throw new IOException("Server closed connection.");
        }

        StringBuilder fullMessage = new StringBuilder();
        fullMessage.append(firstLine).append("\n");

        int code = Integer.parseInt(firstLine.substring(0, 3));

        // FTP multiline reply format:
        // 220-First line
        // text...
        // 220 Last line
        if (firstLine.length() > 3 && firstLine.charAt(3) == '-') {
            String line;

            while ((line = reader.readLine()) != null) {
                fullMessage.append(line).append("\n");

                if (line.startsWith(code + " ")) {
                    break;
                }
            }
        }

        String replyText = fullMessage.toString();
        // Push parsed reply to log stream immediately.
        emitLog(replyText.trim());

        return new FtpReply(code, replyText);
    }

    public static void main(String[] args) {
        FtpClient ftp = new FtpClient();

        try {
            ftp.connect("ftp.dlptest.com", 21);
            ftp.login("dlpuser", "rNrKYTX9g7z3RgJRmxWuGHbeu");

            ftp.uploadFile("test.txt", "student_test_upload.txt");

            ftp.quit();
        } catch (IOException e) {
            System.out.println("FTP error: " + e.getMessage());

            try {
                ftp.close();
            } catch (IOException ignored) {
            }
        }
    }
}
