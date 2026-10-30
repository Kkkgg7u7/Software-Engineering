package com.werewolf.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.werewolf.auth.security.DbUserDetailsService;
import com.werewolf.auth.security.JwtAuthenticationFilter;
import com.werewolf.common.ApiResponse;
import com.werewolf.common.ErrorCode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Spring Security 配置：无状态 JWT 鉴权。
 *
 * <p>放行：注册/登录、静态资源、WebSocket 握手端点、健康检查。
 * 其余接口一律需要 Bearer Token。
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtFilter;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    public SecurityConfig(JwtAuthenticationFilter jwtFilter, AppProperties appProperties,
                          ObjectMapper objectMapper) {
        this.jwtFilter = jwtFilter;
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public DaoAuthenticationProvider daoAuthenticationProvider(DbUserDetailsService uds,
                                                               PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(uds);
        provider.setPasswordEncoder(encoder);
        provider.setHideUserNotFoundExceptions(true);
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration)
            throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/auth/register", "/api/auth/login").permitAll()
                        .requestMatchers("/api/health", "/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/media/avatars/**").permitAll()
                        .requestMatchers("/ws/**", "/ws-native/**").permitAll()
                        .requestMatchers(HttpMethod.GET,"/login","/chat","/games","/wall","/friends","/me","/records","/lobby","/room/*","/game/*","/join/*").permitAll()
                        .requestMatchers("/", "/index.html", "/assets/**", "/favicon.ico").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, e) -> writeError(res, ErrorCode.UNAUTHORIZED))
                        .accessDeniedHandler((req, res, e) -> writeError(res, ErrorCode.FORBIDDEN)))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * CORS 配置。
     *
     * <p><b>为什么要自己实现 CorsConfigurationSource</b>：本项目的部署场景是
     * "一个人开服务、其他人用自己的电脑连进来"，客户端来源可能是
     * {@code http://localhost:5173}、{@code http://192.168.1.23:5173}、
     * {@code http://203.0.113.45:5173}（校园网/企业网常见）、
     * {@code https://xxx.trycloudflare.com} 等任意地址。
     *
     * <p>两种"偷懒"写法都有坑：
     * <ul>
     *   <li>{@code setAllowedOrigins(...)}：只做字符串精确匹配，白名单外一律被浏览器拒绝，
     *       表现为"页面能打开但登录/接口全失败"；</li>
     *   <li>{@code setAllowedOriginPatterns("http://**:*")}：Spring 匹配成功后会把
     *       <b>模式原文</b>回填到 {@code Access-Control-Allow-Origin}，
     *       而浏览器要求该值必须与实际 Origin 完全一致，于是依旧报错。</li>
     * </ul>
     *
     * <p>因此这里按请求动态判定：命中就用 {@link OriginMatcher} 放行，
     * 并把 {@code Access-Control-Allow-Origin} 设成该请求<b>真实的 Origin</b>，
     * 这样既支持通配、支持本机任意网卡地址，也满足浏览器与 {@code credentials} 的要求。
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        return request -> {
            CorsConfiguration config = new CorsConfiguration();
            config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
            config.setAllowedHeaders(List.of("*"));
            config.setAllowCredentials(true);
            config.setMaxAge(3600L);
            String origin = request.getHeader("Origin");
            // 注意：没有 Origin 头的请求（同源请求、curl、MockMvc、部分代理）不需要 CORS 响应头。
            // 这里必须判断 origin != null：List.of(null) 会抛 NullPointerException，
            // 而 Origin 为空的场景在测试与非浏览器客户端里非常常见。
            if (origin != null && !origin.isBlank()
                    && OriginMatcher.matches(origin, appProperties.getCors().getAllowedOrigins())) {
                config.setAllowedOrigins(List.of(origin));
            }
            return config;
        };
    }

    private void writeError(jakarta.servlet.http.HttpServletResponse response, ErrorCode code)
            throws java.io.IOException {
        response.setStatus(200);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.fail(code)));
    }
}
