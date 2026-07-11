package de.hauschel.arknet.core;

public record ValidationResult(String focusNode, String path, String message, Severity severity) {

    public enum Severity { VIOLATION, WARNING }

    public boolean isViolation() {
        return severity == Severity.VIOLATION;
    }
}
