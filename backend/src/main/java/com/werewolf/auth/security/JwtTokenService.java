package com.werewolf.auth.security;

import com.werewolf.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 签发与校验。使用 HS256，密钥来自 werewolf.jwt.secret。
 */
@Component
public class JwtTokenService {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenService.class);

    private final SecretKey key;
    private final long expireMillis;
    private final String issuer;

    public JwtTokenService(AppProperties properties) {
        String secret = properties.getJwt().getSecret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("werewolf.jwt.secret 至少需要 32 字节");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireMillis = properties.getJwt().getExpireMinutes() * 60_000L;
        this.issuer = properties.getJwt().getIssuer();
    }

    public String generate(UserPrincipal principal) {
        Date now = new Date();
        return Jwts.builder()
                .issuer(issuer)
                .subject(String.valueOf(principal.getId()))
                .claim("username", principal.getUsername())
                .claim("nickname", principal.getNickname())
                // 认证版本号：登录时递增，服务端据此让旧 Token 失效（单会话强制）
                .claim("ver", principal.getAuthVersion())
                .id(java.util.UUID.randomUUID().toString())
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expireMillis))
                .signWith(key)
                .compact();
    }

    /** 解析并验签；失败返回 null（调用方按未登录处理）。 */
    public Claims parse(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String raw = token.startsWith("Bearer ") ? token.substring(7).trim() : token.trim();
        try {
            return Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(issuer)
                    .build()
                    .parseSignedClaims(raw)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException ex) {
            log.debug("JWT 校验失败: {}", ex.getMessage());
            return null;
        }
    }

    public long getExpireMillis() {
        return expireMillis;
    }
}
