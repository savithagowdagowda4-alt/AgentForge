package com.agentforge.auth;

import com.agentforge.common.ApiException;
import com.agentforge.common.Db;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

/** Account registration and password verification (PBKDF2-HMAC-SHA256, per-user salt). */
@Service
public class AuthService {
    private static final int ITERATIONS = 120_000;
    private final Db db;
    private final SecureRandom random = new SecureRandom();

    public AuthService(Db db) {
        this.db = db;
    }

    public Map<String, Object> register(String username, String password, String displayName) {
        String u = normalize(username);
        if (password == null || password.length() < 6) throw ApiException.bad("Password must be at least 6 characters");
        if (db.one("SELECT id FROM users WHERE username=?", u).isPresent())
            throw ApiException.conflict("That username is already taken");
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        String saltB64 = Base64.getEncoder().encodeToString(salt);
        long id = db.insert("users", Db.row(
                "username", u,
                "display_name", displayName == null || displayName.isBlank() ? u : displayName.trim(),
                "pw_hash", hash(password, salt),
                "salt", saltB64));
        return profile(id);
    }

    public Map<String, Object> login(String username, String password) {
        Map<String, Object> row = db.one("SELECT * FROM users WHERE username=?", normalize(username))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Invalid username or password"));
        byte[] salt = Base64.getDecoder().decode(Db.str(row, "salt"));
        String expected = Db.str(row, "pw_hash");
        if (!MessageDigest.isEqual(expected.getBytes(), hash(password == null ? "" : password, salt).getBytes()))
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
        return profile(Db.lng(row, "id"));
    }

    public Map<String, Object> profile(long id) {
        return db.one("SELECT id, username, display_name, created_at FROM users WHERE id=?", id)
                .orElseThrow(() -> ApiException.notFound("User"));
    }

    private static String normalize(String username) {
        if (username == null || !username.trim().matches("[A-Za-z0-9_.-]{3,32}"))
            throw ApiException.bad("Username must be 3-32 characters: letters, digits, _ . -");
        return username.trim().toLowerCase();
    }

    private static String hash(String password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256);
            byte[] out = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("Password hashing unavailable", e);
        }
    }
}
