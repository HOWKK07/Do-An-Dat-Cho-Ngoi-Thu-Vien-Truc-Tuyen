package library.common;

public enum Role {
    STUDENT("Sinh viên"),
    ADMIN("Quản trị viên");

    private final String label;

    Role(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    @Override
    public String toString() {
        return label;
    }
}
