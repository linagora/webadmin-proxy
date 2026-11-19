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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class AllowedUrl {

    /**
     * A request target, as every rule reads it. What only depends on the request (the segment-wise decoded
     * path, the decoded query parameters) is computed once, on first use, and shared by all the rules tried
     * for this request.
     */
    public static final class Target {
        private final String method;
        private final String upperCaseMethod;
        private final String rawPath;
        private final String rawQuery;
        private String decodedPath;
        private Map<String, List<String>> queryParams;

        private Target(String method, String rawPath, String rawQuery) {
            this.method = method;
            this.upperCaseMethod = method.toUpperCase(Locale.ROOT);
            this.rawPath = rawPath;
            this.rawQuery = rawQuery;
        }

        public static Target of(String method, String fullUri) {
            int queryIdx = fullUri.indexOf('?');
            String rawPath = queryIdx >= 0 ? fullUri.substring(0, queryIdx) : fullUri;
            String rawQuery = queryIdx >= 0 ? fullUri.substring(queryIdx + 1) : "";
            return new Target(method, rawPath, rawQuery);
        }

        public String method() {
            return method;
        }

        public String rawQuery() {
            return rawQuery;
        }

        String decodedPath() {
            if (decodedPath == null) {
                decodedPath = segmentWiseDecodedPath(rawPath);
            }
            return decodedPath;
        }

        Map<String, List<String>> queryParams() {
            if (queryParams == null) {
                queryParams = parseQueryString(rawQuery);
            }
            return queryParams;
        }
    }

    private record CompiledQueryParam(Pattern pattern, List<String> variableNames, Map<String, String> groupNames) {
        CompiledQueryParam {
            variableNames = List.copyOf(variableNames);
            groupNames = Map.copyOf(groupNames);
        }
    }

    private final List<String> verbs;
    private final Set<String> coveredVerbs;
    private final String endpointPattern;
    private final boolean denied;
    private final Pattern compiledPathPattern;
    private final List<String> pathVariableNames;
    private final Map<String, String> pathGroupNames;
    private final Map<String, CompiledQueryParam> compiledQueryParams;

    public AllowedUrl(List<String> verbs, String endpointPattern) {
        this(verbs, endpointPattern, false);
    }

    public AllowedUrl(List<String> verbs, String endpointPattern, boolean denied) {
        this.verbs = List.copyOf(verbs);
        this.coveredVerbs = coveredVerbs(this.verbs);
        this.endpointPattern = endpointPattern;
        this.denied = denied;

        int queryIdx = endpointPattern.indexOf('?');
        String pathPart = queryIdx >= 0 ? endpointPattern.substring(0, queryIdx) : endpointPattern;
        String queryPart = queryIdx >= 0 ? endpointPattern.substring(queryIdx + 1) : "";

        List<String> names = new ArrayList<>();
        Map<String, String> groupNames = new HashMap<>();
        this.compiledPathPattern = compile(toPathRegex(pathPart, names, groupNames), endpointPattern);
        this.pathVariableNames = List.copyOf(names);
        this.pathGroupNames = Map.copyOf(groupNames);
        this.compiledQueryParams = parseQueryPattern(queryPart, endpointPattern);
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
        return match(Target.of(method, fullUri));
    }

    /**
     * Same as {@link #match(String, String)}, for a target built once and shared by all the rules tried.
     */
    public Optional<Map<String, String>> match(Target target) {
        if (!coversVerb(target.upperCaseMethod)) {
            return Optional.empty();
        }

        Matcher m = compiledPathPattern.matcher(target.decodedPath());
        if (!m.matches()) {
            return Optional.empty();
        }

        Map<String, String> captured = new HashMap<>();
        pathVariableNames.forEach(varName -> captured.put(varName, decodePath(m.group(pathGroupNames.get(varName)))));

        if (compiledQueryParams.isEmpty()) {
            return Optional.of(captured);
        }
        Map<String, List<String>> requestParams = target.queryParams();
        for (Map.Entry<String, CompiledQueryParam> entry : compiledQueryParams.entrySet()) {
            String paramName = entry.getKey();
            CompiledQueryParam cqp = entry.getValue();
            List<String> requestValues = requestParams.get(paramName);
            if (requestValues == null) {
                return Optional.empty();
            }
            if (requestValues.size() > 1) {
                // Which value the backend reads is not ours to guess: an allow does not grant it, a deny refuses it.
                if (denied) {
                    continue;
                }
                return Optional.empty();
            }
            Matcher qm = cqp.pattern().matcher(requestValues.get(0));
            if (!qm.matches()) {
                return Optional.empty();
            }
            for (String varName : cqp.variableNames()) {
                String groupValue = qm.group(cqp.groupNames().get(varName));
                String existing = captured.get(varName);
                if (existing != null && !existing.equals(groupValue)) {
                    return Optional.empty();
                }
                captured.put(varName, groupValue);
            }
        }

        return Optional.of(captured);
    }

    /**
     * A rule listing GET also covers HEAD: HEAD is GET without content (RFC 9110 section 9.3.2) and the
     * backend serves it with its GET route, so a GET deny rule must refuse it too.
     * Verbs are compared case-insensitively: both sides are upper-cased, the rule's verbs once and for all.
     */
    private static Set<String> coveredVerbs(List<String> verbs) {
        Set<String> covered = new HashSet<>();
        for (String verb : verbs) {
            String upperCaseVerb = verb.toUpperCase(Locale.ROOT);
            covered.add(upperCaseVerb);
            if (upperCaseVerb.equals("GET")) {
                covered.add("HEAD");
            }
        }
        return Set.copyOf(covered);
    }

    private boolean coversVerb(String upperCaseMethod) {
        return verbs.isEmpty() || coveredVerbs.contains(upperCaseMethod);
    }

    public boolean matches(String method, String fullUri) {
        return match(method, fullUri).isPresent();
    }

    private static Map<String, CompiledQueryParam> parseQueryPattern(String queryPart, String endpointPattern) {
        if (queryPart.isEmpty()) {
            return Map.of();
        }
        Map<String, CompiledQueryParam> result = new HashMap<>();
        for (String param : queryPart.split("&")) {
            if (isOtherParamsPlaceholder(param)) {
                // '{params}' stands for "any other parameters": unlisted parameters are ignored anyway.
                continue;
            }
            int eq = param.indexOf('=');
            if (eq < 0) {
                // Valueless 'flag': the parameter must be present, with any value or none.
                putOnce(result, param, new CompiledQueryParam(Pattern.compile("^.*$", Pattern.DOTALL), List.of(), Map.of()), endpointPattern);
                continue;
            }
            String paramName = param.substring(0, eq);
            String valuePattern = param.substring(eq + 1);
            List<String> varNames = new ArrayList<>();
            Map<String, String> groupNames = new HashMap<>();
            Pattern compiled = compile(toPathRegex(valuePattern, varNames, groupNames), endpointPattern);
            putOnce(result, paramName, new CompiledQueryParam(compiled, varNames, groupNames), endpointPattern);
        }
        return Map.copyOf(result);
    }

    /**
     * A parameter listed twice would otherwise keep only its last constraint, silently widening the rule:
     * such a pattern is refused at startup.
     */
    private static void putOnce(Map<String, CompiledQueryParam> result, String paramName,
                                CompiledQueryParam compiledParam, String endpointPattern) {
        if (result.putIfAbsent(paramName, compiledParam) != null) {
            throw new IllegalArgumentException("Invalid endpoint pattern '" + endpointPattern
                + "': query parameter '" + paramName + "' is listed more than once");
        }
    }

    private static boolean isOtherParamsPlaceholder(String param) {
        return param.length() > 2 && param.startsWith("{") && param.endsWith("}")
            && param.indexOf('{', 1) < 0 && param.indexOf('=') < 0;
    }

    /**
     * DOTALL: '*' matches what it is decoded to, line terminators included ({@code %0A}, {@code %E2%80%A8}...),
     * otherwise a deny rule written with '*' would be skipped for such a target.
     */
    private static Pattern compile(String regex, String endpointPattern) {
        try {
            return Pattern.compile(regex, Pattern.DOTALL);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("Invalid endpoint pattern '" + endpointPattern + "': " + e.getDescription(), e);
        }
    }

    /**
     * Percent-decodes a request path per RFC 3986: unlike form decoding, '+' is kept literally,
     * which is how backends read paths.
     */
    static String decodePath(String rawPath) {
        return URLDecoder.decode(rawPath.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    /**
     * The backend splits the raw path on '/' before decoding each segment: an encoded slash ({@code %2F})
     * stays inside its segment. Each segment is thus decoded on its own, and its '%' and '/' re-escaped, so
     * that a '{var}' or '%' never spans more than one backend segment. Captured values are decoded afterwards.
     */
    static String segmentWiseDecodedPath(String rawPath) {
        if (rawPath.indexOf('%') < 0) {
            // Nothing to decode: '+' is kept literally and no segment holds a '/', the path is its own result.
            return rawPath;
        }
        StringBuilder result = new StringBuilder(rawPath.length());
        int start = 0;
        while (true) {
            int end = rawPath.indexOf('/', start);
            appendDecodedSegment(result, rawPath, start, end < 0 ? rawPath.length() : end);
            if (end < 0) {
                return result.toString();
            }
            result.append('/');
            start = end + 1;
        }
    }

    /**
     * A segment without '%' has nothing to decode nor to re-escape: it is copied as is, in bulk.
     */
    private static void appendDecodedSegment(StringBuilder result, String rawPath, int start, int end) {
        if (rawPath.indexOf('%', start, end) < 0) {
            result.append(rawPath, start, end);
            return;
        }
        String decodedSegment = decodePath(rawPath.substring(start, end));
        if (decodedSegment.indexOf('%') < 0 && decodedSegment.indexOf('/') < 0) {
            result.append(decodedSegment);
            return;
        }
        result.append(decodedSegment.replace("%", "%25").replace("/", "%2F"));
    }

    private static Map<String, List<String>> parseQueryString(String queryString) {
        if (queryString.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> result = HashMap.newHashMap(countParams(queryString));
        for (String param : queryString.split("&")) {
            int eq = param.indexOf('=');
            String name = URLDecoder.decode(eq >= 0 ? param.substring(0, eq) : param, StandardCharsets.UTF_8);
            String value = URLDecoder.decode(eq >= 0 ? param.substring(eq + 1) : "", StandardCharsets.UTF_8);
            addValue(result, name, value);
        }
        return result;
    }

    /**
     * Every occurrence is kept: a repeated name is how a rule tells a multi-valued parameter. Most names
     * appear once and get a compact single-value list; only a repeated one is given a growable list.
     */
    private static void addValue(Map<String, List<String>> params, String name, String value) {
        List<String> values = params.get(name);
        if (values == null) {
            params.put(name, List.of(value));
        } else if (values instanceof ArrayList<String> growable) {
            growable.add(value);
        } else {
            List<String> multiValued = new ArrayList<>();
            multiValued.add(values.getFirst());
            multiValued.add(value);
            params.put(name, multiValued);
        }
    }

    private static int countParams(String queryString) {
        int count = 1;
        for (int i = queryString.indexOf('&'); i >= 0; i = queryString.indexOf('&', i + 1)) {
            count++;
        }
        return count;
    }

    /**
     * Variables are compiled to generated group names, so that any variable name is accepted and a
     * variable repeated within the same pattern becomes a back-reference: all its occurrences must
     * capture the same value.
     */
    private static String toPathRegex(String pattern, List<String> variableNames, Map<String, String> groupNames) {
        StringBuilder sb = new StringBuilder("^");
        int i = 0;
        while (i < pattern.length()) {
            char c = pattern.charAt(i);
            if (c == '{') {
                int end = pattern.indexOf('}', i);
                if (end != -1) {
                    String varName = pattern.substring(i + 1, end);
                    String existingGroup = groupNames.get(varName);
                    if (existingGroup != null) {
                        sb.append("\\k<").append(existingGroup).append(">");
                    } else {
                        String groupName = "v" + groupNames.size();
                        groupNames.put(varName, groupName);
                        variableNames.add(varName);
                        sb.append("(?<").append(groupName).append(">[^/]+)");
                    }
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
