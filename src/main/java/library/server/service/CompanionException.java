package library.server.service;

/**
 * Lỗi ở danh sách người đi cùng khi đặt cả bàn (thiếu người, sai MSSV, người đó đã có chỗ khác...).
 * Client nhận được cờ {@code companionError} để mở lại hộp khai báo cho sinh viên sửa.
 */
public class CompanionException extends ServiceException {
    private static final long serialVersionUID = 1L;

    public CompanionException(String message) {
        super(message);
    }
}
