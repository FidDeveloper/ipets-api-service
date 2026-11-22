package com.ipets;

import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/** Input rules shared by register/login; mirrors the mobile sign-up validation. */
final class Policy {
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]{1,64}@[^\\s@]+\\.[^\\s@]{2,}$");

    private Policy() {}

    static String email(String raw) {
        String email = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (email.length() > 254 || !EMAIL.matcher(email).matches()) throw new ApiError(HttpStatus.BAD_REQUEST, "invalid_email", "Enter a valid email address.");
        return email;
    }

    static String name(String raw) {
        String name = raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
        if (name.length() < 2 || name.length() > 100) throw new ApiError(HttpStatus.BAD_REQUEST, "invalid_name", "Enter a name of 2 to 100 characters.");
        return name;
    }

    static String password(String raw) {
        if (raw == null || raw.length() < 8 || raw.length() > 128 || !raw.matches(".*[A-Za-z].*") || !raw.matches(".*\\d.*"))
            throw new ApiError(HttpStatus.BAD_REQUEST, "weak_password", "Use 8 to 128 characters with at least one letter and one number.");
        return raw;
    }
}
