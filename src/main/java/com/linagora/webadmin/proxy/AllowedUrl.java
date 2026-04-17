/********************************************************************
 *  Webadmin Proxy                                                   *
 *                                                                   *
 *  Copyright (C) 2025 Linagora                                      *
 *                                                                   *
 *  This program is free software: you can redistribute it and/or   *
 *  modify it under the terms of the GNU Affero General Public       *
 *  License as published by the Free Software Foundation, either     *
 *  version 3 of the License, or (at your option) any later version. *
 *                                                                   *
 *  https://www.gnu.org/licenses/agpl-3.0.en.html                   *
 ********************************************************************/

package com.linagora.webadmin.proxy;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AllowedUrl {

    private record CompiledQueryParam(Pattern pattern, List<String> variableNames) {}

    private final List<String> verbs;
    private final String endpointPattern;
    private final boolean denied;
    private final Pattern compiledPathPattern;
    private final List<String> pathVariableNames;
    private final Map<String, CompiledQueryParam> compiledQueryParams;

    public AllowedUrl(List<String> verbs, String endpointPattern) {
        this(verbs, endpointPattern, false);
    }

    public AllowedUrl(List<String> verbs, String endpointPattern, boolean denied) {
        this.verbs = List.copyOf(verbs);
        this.endpointPattern = endpointPattern;
        this.denied = denied;

        int queryIdx = endpointPattern.indexOf('?');
        String pathPart = queryIdx >= 0 ? endpointPattern.substring(0, queryIdx) : endpointPattern;
        String queryPart = queryIdx >= 0 ? endpointPattern.substring(queryIdx + 1) : "";

        List<String> names = new ArrayList<>();
        this.compiledPathPattern = Pattern.compile(toPathRegex(pathPart, names));
        this.pathVariableNames = List.copyOf(names);
        this.compiledQueryParams = parseQueryPattern(queryPart);
    }

    public List<String> verbs() {
        return verbs;
    }

    public String endpointPattern() {
        return endpointPattern;
    }

    public boolean isDenied() {
        return denied;
    }

    /**
     * Returns the captured template variable values (from both path and query) if this rule
     * matches the given method and full URI, empty otherwise.
     */
    public Optional<Map<String, String>> match(String method, String fullUri) {
        if (!verbs.isEmpty() && verbs.stream().noneMatch(v -> v.equalsIgnoreCase(method))) {
            return Optional.empty();
        }

        int queryIdx = fullUri.indexOf('?');
        String rawPath = queryIdx >= 0 ? fullUri.substring(0, queryIdx) : fullUri;
        String queryString = queryIdx >= 0 ? fullUri.substring(queryIdx + 1) : "";

        String path = URLDecoder.decode(rawPath, StandardCharsets.UTF_8);
        Matcher m = compiledPathPattern.matcher(path);
        if (!m.matches()) {
            return Optional.empty();
        }

        Map<String, String> captured = new HashMap<>();
        for (String varName : pathVariableNames) {
            captured.put(varName, m.group(varName));
        }

        Map<String, String> requestParams = parseQueryString(queryString);
        for (Map.Entry<String, CompiledQueryParam> entry : compiledQueryParams.entrySet()) {
            String paramName = entry.getKey();
            CompiledQueryParam cqp = entry.getValue();
            String requestValue = requestParams.get(paramName);
            if (requestValue == null) {
                return Optional.empty();
            }
            Matcher qm = cqp.pattern().matcher(requestValue);
            if (!qm.matches()) {
                return Optional.empty();
            }
            for (String varName : cqp.variableNames()) {
                String groupValue = qm.group(varName);
                String existing = captured.get(varName);
                if (existing != null && !existing.equals(groupValue)) {
                    return Optional.empty();
                }
                captured.put(varName, groupValue);
            }
        }

        return Optional.of(captured);
    }

    public boolean matches(String method, String fullUri) {
        return match(method, fullUri).isPresent();
    }

    private static Map<String, CompiledQueryParam> parseQueryPattern(String queryPart) {
        if (queryPart.isEmpty()) {
            return Map.of();
        }
        Map<String, CompiledQueryParam> result = new HashMap<>();
        for (String param : queryPart.split("&")) {
            int eq = param.indexOf('=');
            if (eq >= 0) {
                String paramName = param.substring(0, eq);
                String valuePattern = param.substring(eq + 1);
                List<String> varNames = new ArrayList<>();
                Pattern compiled = Pattern.compile(toPathRegex(valuePattern, varNames));
                result.put(paramName, new CompiledQueryParam(compiled, List.copyOf(varNames)));
            }
        }
        return Map.copyOf(result);
    }

    private static Map<String, String> parseQueryString(String queryString) {
        if (queryString.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new HashMap<>();
        for (String param : queryString.split("&")) {
            int eq = param.indexOf('=');
            if (eq >= 0) {
                result.put(
                    URLDecoder.decode(param.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(param.substring(eq + 1), StandardCharsets.UTF_8));
            } else {
                result.put(URLDecoder.decode(param, StandardCharsets.UTF_8), "");
            }
        }
        return result;
    }

    private static String toPathRegex(String pattern, List<String> variableNames) {
        StringBuilder sb = new StringBuilder("^");
        int i = 0;
        while (i < pattern.length()) {
            char c = pattern.charAt(i);
            if (c == '{') {
                int end = pattern.indexOf('}', i);
                if (end != -1) {
                    String varName = pattern.substring(i + 1, end);
                    variableNames.add(varName);
                    sb.append("(?<").append(varName).append(">[^/]+)");
                    i = end + 1;
                } else {
                    sb.append(Pattern.quote(String.valueOf(c)));
                    i++;
                }
            } else if (c == '%') {
                sb.append("[^@/]+");
                i++;
            } else if (c == '*') {
                sb.append(".*");
                i++;
            } else {
                int next = i;
                while (next < pattern.length() && pattern.charAt(next) != '{' && pattern.charAt(next) != '*' && pattern.charAt(next) != '%') {
                    next++;
                }
                sb.append(Pattern.quote(pattern.substring(i, next)));
                i = next;
            }
        }
        sb.append("$");
        return sb.toString();
    }
}
