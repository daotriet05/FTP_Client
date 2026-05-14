package trietdao.ftp_client;

public class FtpReplyCodes {
    public static final int SERVICE_READY = 220;
    public static final int USER_LOGGED_IN = 230;
    public static final int NEED_PASSWORD = 331;

    public static final int COMMAND_OK = 200;
    public static final int PATH_CREATED = 257;
    public static final int ACTION_COMPLETED = 250;

    public static final int ENTERING_PASSIVE_MODE = 227;

    public static final int DATA_ALREADY_OPEN = 125;
    public static final int OPENING_DATA_CONNECTION = 150;
    public static final int TRANSFER_COMPLETE = 226;

    public static final int SERVICE_CLOSING = 221;

    public static final int NOT_LOGGED_IN = 530;
    public static final int FILE_UNAVAILABLE = 550;
    public static final int CANNOT_OPEN_DATA_CONNECTION = 425;
}
