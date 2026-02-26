package com.gczm.aimeeting.service;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;

@Service
public class SecurityService {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final SecureRandom secureRandom = new SecureRandom();

    public String hashPasscode(String passcode) {
        return encoder.encode(passcode);
    }

    public boolean verifyPasscode(String passcode, String hashed) {
        return encoder.matches(passcode, hashed);
    }

    public String generateShareToken() {
        byte[] bytes = new byte[18];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
