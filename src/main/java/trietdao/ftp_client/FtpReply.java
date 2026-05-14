package trietdao.ftp_client;

public class FtpReply {
    public final int code;
    public final String message;

    public FtpReply(int code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override
    public String toString() {
        return code + " " + message;
    }
}
