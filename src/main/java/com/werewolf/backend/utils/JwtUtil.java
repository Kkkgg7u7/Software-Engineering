package com.werewolf.backend.utils;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;

import java.util.Date;
import java.util.Map;

public class JwtUtil {

    // 门禁卡防伪密钥（极其重要！只有咱们后端知道，黑客拿不到）
    private static final String KEY = "Werewolf_DaLangWang_888";

    // 功能1：制卡（接收用户数据，生成防伪 Token）
    public static String genToken(Map<String, Object> claims) {
        return JWT.create()
                .withClaim("claims", claims) // 把用户数据印在卡上
                .withExpiresAt(new Date(System.currentTimeMillis() + 1000 * 60 * 60 * 12)) // 门禁卡12小时后自动过期
                .sign(Algorithm.HMAC256(KEY)); // 盖上防伪公章！
    }

    // 功能2：验卡（接收 Token，验证真伪，并把上面的用户数据抠出来）
    public static Map<String, Object> parseToken(String token) {
        return JWT.require(Algorithm.HMAC256(KEY))
                .build()
                .verify(token) // 如果是假卡、或者过期了，这里会直接报错拦截！
                .getClaim("claims")
                .asMap();
    }
}