package com.rlna.config;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

/**
 * Neon and Render both hand out a libpq-style {@code postgresql://user:pass@host/db}
 * URL, which JDBC cannot consume directly. Translating it here means the same
 * DATABASE_URL works locally, in Docker, and on Render without a second
 * hand-maintained JDBC variable that can drift out of sync.
 */
public class DatabaseUrlEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String SOURCE_NAME = "rlnaDatabaseUrl";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String raw = environment.getProperty("DATABASE_URL");
        if (!StringUtils.hasText(raw) || raw.startsWith("jdbc:")) {
            return;
        }

        URI uri = URI.create(raw);
        String userInfo = uri.getUserInfo();
        String username = null;
        String password = null;
        if (StringUtils.hasText(userInfo)) {
            int split = userInfo.indexOf(':');
            username = split >= 0 ? decode(userInfo.substring(0, split)) : decode(userInfo);
            password = split >= 0 ? decode(userInfo.substring(split + 1)) : null;
        }

        StringBuilder jdbc = new StringBuilder("jdbc:postgresql://")
                .append(uri.getHost());
        if (uri.getPort() > 0) {
            jdbc.append(':').append(uri.getPort());
        }
        jdbc.append(uri.getPath());
        // Credentials are passed as DataSource properties, never in the URL, so
        // they cannot leak into a logged connection string.
        String query = stripCredentials(uri.getQuery());
        if (StringUtils.hasText(query)) {
            jdbc.append('?').append(query);
        }

        Map<String, Object> props = new HashMap<>();
        props.put("spring.datasource.url", jdbc.toString());
        if (username != null) {
            props.put("spring.datasource.username", username);
        }
        if (password != null) {
            props.put("spring.datasource.password", password);
        }
        environment.getPropertySources().addFirst(new MapPropertySource(SOURCE_NAME, props));
    }

    private static String stripCredentials(String query) {
        if (!StringUtils.hasText(query)) {
            return query;
        }
        StringBuilder kept = new StringBuilder();
        for (String part : query.split("&")) {
            String key = part.split("=", 2)[0].toLowerCase();
            if (key.equals("user") || key.equals("password")) {
                continue;
            }
            if (kept.length() > 0) {
                kept.append('&');
            }
            kept.append(part);
        }
        return kept.toString();
    }

    private static String decode(String value) {
        return java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8);
    }
}
