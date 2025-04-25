package com.hmdp.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;

import java.nio.charset.StandardCharsets;
import java.util.Date;

public class JWTUtils {
    private static Long expire = 7 * 24 * 3600 * 1000L; // 7 天
    private static String secretKey = "hmdp:something-really-secret";
    public static String generateToken(Long userId) {
        Date now = new Date();
        Date expiration = new Date(now.getTime() + expire);
        return Jwts.builder()
                .setHeaderParam("type", "JWT")
                .setSubject(userId.toString())
                .setIssuedAt(now)
                .setExpiration(expiration)
                // HS512 比 HS256 更安全，显式指定字符编码避免平台差异问题
                .signWith(SignatureAlgorithm.HS512, secretKey.getBytes(StandardCharsets.UTF_8))
                .compact();
    }

    public static Claims parseToken(String token) {
        return Jwts.parser()
                .setSigningKey(secretKey.getBytes(StandardCharsets.UTF_8))
                .parseClaimsJws(token)
                .getBody();
    }
}
