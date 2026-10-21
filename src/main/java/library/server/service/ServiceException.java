package library.server.service;

/** Lỗi nghiệp vụ - thông điệp sẽ được gửi nguyên văn cho người dùng. */
public class ServiceException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ServiceException(String message) {
        super(message);
    }
}
