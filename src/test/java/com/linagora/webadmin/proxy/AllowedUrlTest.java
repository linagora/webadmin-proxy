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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.linagora.webadmin.proxy.UrlPatternRestriction.Operator;

class AllowedUrlTest {

    // --- Template variable: single segment only ---

    @Test
    void templateVariableShouldMatchSingleSegment() {
        AllowedUrl rule = new AllowedUrl(List.of(), "/abc/{def}/ghi");
        assertThat(rule.matches("GET", "/abc/xxx/ghi")).isTrue();
    }

    @Test
    void templateVariableShouldNotMatchMultipleSegments() {
        AllowedUrl rule = new AllowedUrl(List.of(), "/abc/{def}/ghi");
        assertThat(rule.matches("GET", "/abc/xxx/yyy/ghi")).isFalse();
    }

    @Test
    void templateVariableShouldNotMatchEmptySegment() {
        AllowedUrl rule = new AllowedUrl(List.of(), "/abc/{def}/ghi");
        assertThat(rule.matches("GET", "/abc//ghi")).isFalse();
    }

    @Test
    void templateVariableShouldMatchSpecialCharsWithinSegment() {
        AllowedUrl rule = new AllowedUrl(List.of(), "/users/{user}/quota");
        assertThat(rule.matches("GET", "/users/bob@example.com/quota")).isTrue();
    }

    @Test
    void templateVariableAtEndShouldNotMatchFurtherSegments() {
        AllowedUrl rule = new AllowedUrl(List.of(), "/domains/{domain}");
        assertThat(rule.matches("GET", "/domains/example.com/extra")).isFalse();
    }

    @Test
    void multipleTemplateVariablesShouldEachMatchOneSegment() {
        AllowedUrl rule = new AllowedUrl(List.of(), "/a/{x}/{y}/b");
        assertThat(rule.matches("GET", "/a/foo/bar/b")).isTrue();
        assertThat(rule.matches("GET", "/a/foo/bar/baz/b")).isFalse();
    }

    // --- Wildcard ---

    @Test
    void wildcardShouldMatchAnything() {
        AllowedUrl rule = new AllowedUrl(List.of(), "/domains/{domain}/aliases/*");
        assertThat(rule.matches("GET", "/domains/example.com/aliases/bob@example.com")).isTrue();
    }

    @Test
    void wildcardShouldMatchMultipleSegments() {
        AllowedUrl rule = new AllowedUrl(List.of(), "/prefix/*");
        assertThat(rule.matches("GET", "/prefix/a/b/c")).isTrue();
    }

    @Test
    void wildcardShouldMatchEmptySuffix() {
        AllowedUrl rule = new AllowedUrl(List.of(), "/prefix/*");
        assertThat(rule.matches("GET", "/prefix/")).isTrue();
    }

    @Test
    void wildcardShouldNotMatchUnrelatedPrefix() {
        AllowedUrl rule = new AllowedUrl(List.of(), "/prefix/*");
        assertThat(rule.matches("GET", "/other/foo")).isFalse();
    }

    // --- Exact match ---

    @Test
    void exactPatternShouldMatchExactPath() {
        AllowedUrl rule = new AllowedUrl(List.of(), "/domains");
        assertThat(rule.matches("GET", "/domains")).isTrue();
    }

    @Test
    void exactPatternShouldNotMatchPrefix() {
        AllowedUrl rule = new AllowedUrl(List.of(), "/domains");
        assertThat(rule.matches("GET", "/domains/example.com")).isFalse();
    }

    @Test
    void exactPatternShouldNotMatchLongerPath() {
        AllowedUrl rule = new AllowedUrl(List.of(), "/abc");
        assertThat(rule.matches("GET", "/abcdef")).isFalse();
    }

    // --- Verb matching ---

    @Test
    void emptyVerbListShouldAllowAllVerbs() {
        AllowedUrl rule = new AllowedUrl(List.of(), "/domains");
        assertThat(rule.matches("GET", "/domains")).isTrue();
        assertThat(rule.matches("POST", "/domains")).isTrue();
        assertThat(rule.matches("DELETE", "/domains")).isTrue();
    }

    @Test
    void verbMatchShouldBeCaseInsensitive() {
        AllowedUrl rule = new AllowedUrl(List.of("GET"), "/domains");
        assertThat(rule.matches("get", "/domains")).isTrue();
        assertThat(rule.matches("Get", "/domains")).isTrue();
    }

    @Test
    void shouldRejectVerbNotInList() {
        AllowedUrl rule = new AllowedUrl(List.of("GET", "PUT"), "/domains");
        assertThat(rule.matches("DELETE", "/domains")).isFalse();
    }

    @Test
    void shouldAllowVerbInList() {
        AllowedUrl rule = new AllowedUrl(List.of("GET", "PUT"), "/domains");
        assertThat(rule.matches("PUT", "/domains")).isTrue();
    }

    @Test
    void shouldRejectWhenVerbMatchesButPathDoesNot() {
        AllowedUrl rule = new AllowedUrl(List.of("GET"), "/domains");
        assertThat(rule.matches("GET", "/users")).isFalse();
    }

    @Nested
    class QueryParameterMatching {

        @Test
        void shouldMatchWhenRequiredQueryParamPresent() {
            AllowedUrl rule = new AllowedUrl(List.of(), "/users?domain={domain}");
            assertThat(rule.matches("GET", "/users?domain=example.com")).isTrue();
        }

        @Test
        void shouldNotMatchWhenRequiredQueryParamMissing() {
            AllowedUrl rule = new AllowedUrl(List.of(), "/users?domain={domain}");
            assertThat(rule.matches("GET", "/users")).isFalse();
        }

        @Test
        void shouldNotMatchWhenRequiredQueryParamHasDifferentName() {
            AllowedUrl rule = new AllowedUrl(List.of(), "/users?domain={domain}");
            assertThat(rule.matches("GET", "/users?tenant=example.com")).isFalse();
        }

        @Test
        void shouldMatchWithExtraQueryParams() {
            AllowedUrl rule = new AllowedUrl(List.of(), "/users?domain={domain}");
            assertThat(rule.matches("GET", "/users?domain=example.com&limit=10")).isTrue();
        }

        @Test
        void shouldMatchLiteralQueryParamValue() {
            AllowedUrl rule = new AllowedUrl(List.of(), "/users?active=true");
            assertThat(rule.matches("GET", "/users?active=true")).isTrue();
            assertThat(rule.matches("GET", "/users?active=false")).isFalse();
        }

        @Test
        void shouldCaptureQueryParamVariable() {
            AllowedUrl rule = new AllowedUrl(List.of(), "/users?domain={domain}");
            Optional<Map<String, String>> result = rule.match("GET", "/users?domain=example.com");
            assertThat(result).isPresent();
            assertThat(result.get()).containsEntry("domain", "example.com");
        }

        @Test
        void shouldCaptureQueryParamAndPathVariableTogether() {
            AllowedUrl rule = new AllowedUrl(List.of(), "/domains/{domain}/users?limit={limit}");
            Optional<Map<String, String>> result = rule.match("GET", "/domains/example.com/users?limit=10");
            assertThat(result).isPresent();
            assertThat(result.get())
                .containsEntry("domain", "example.com")
                .containsEntry("limit", "10");
        }

        @Test
        void shouldDecodeUrlEncodedQueryParamValue() {
            AllowedUrl rule = new AllowedUrl(List.of(), "/users?domain={domain}");
            Optional<Map<String, String>> result = rule.match("GET", "/users?domain=example%2Ecom");
            assertThat(result).isPresent();
            assertThat(result.get()).containsEntry("domain", "example.com");
        }

        @Test
        void shouldNotMatchPathWithoutQueryWhenQueryRequired() {
            AllowedUrl rule = new AllowedUrl(List.of(), "/users?domain={domain}");
            assertThat(rule.matches("GET", "/users")).isFalse();
        }

        @Test
        void patternWithoutQueryShouldMatchPathWithQueryParams() {
            AllowedUrl rule = new AllowedUrl(List.of(), "/users");
            assertThat(rule.matches("GET", "/users?limit=10")).isTrue();
        }
    }

    @Nested
    class CapturedVariables {

        @Test
        void matchShouldReturnEmptyWhenNotMatched() {
            AllowedUrl rule = new AllowedUrl(List.of(), "/domains/{domain}");
            assertThat(rule.match("GET", "/users/foo")).isEmpty();
        }

        @Test
        void matchShouldReturnCapturedVariable() {
            AllowedUrl rule = new AllowedUrl(List.of(), "/domains/{domain}");
            Optional<Map<String, String>> result = rule.match("GET", "/domains/example.com");
            assertThat(result).isPresent();
            assertThat(result.get()).containsEntry("domain", "example.com");
        }

        @Test
        void matchShouldReturnMultipleCapturedVariables() {
            AllowedUrl rule = new AllowedUrl(List.of(), "/a/{x}/{y}/b");
            Optional<Map<String, String>> result = rule.match("GET", "/a/foo/bar/b");
            assertThat(result).isPresent();
            assertThat(result.get()).containsEntry("x", "foo").containsEntry("y", "bar");
        }

        @Test
        void matchShouldReturnEmptyMapWhenNoVariables() {
            AllowedUrl rule = new AllowedUrl(List.of(), "/domains");
            Optional<Map<String, String>> result = rule.match("GET", "/domains");
            assertThat(result).isPresent();
            assertThat(result.get()).isEmpty();
        }

        @Test
        void matchShouldReturnEmptyWhenVerbMismatches() {
            AllowedUrl rule = new AllowedUrl(List.of("GET"), "/domains/{domain}");
            assertThat(rule.match("DELETE", "/domains/example.com")).isEmpty();
        }
    }

    @Nested
    class OperatorTests {

        @Test
        void equalsShouldReturnClaimValueUnchanged() {
            assertThat(Operator.EQUALS.extractExpectedValue("example.com")).isEqualTo("example.com");
        }

        @Test
        void hasDomainShouldExtractDomainFromEmail() {
            assertThat(Operator.HAS_DOMAIN.extractExpectedValue("bob@example.com")).isEqualTo("example.com");
        }

        @Test
        void hasDomainShouldExtractDomainWhenMultipleAtSigns() {
            // Only the first @ separates local-part from domain
            assertThat(Operator.HAS_DOMAIN.extractExpectedValue("a@b@example.com")).isEqualTo("b@example.com");
        }

        @Test
        void hasDomainShouldThrowWhenEmailHasNoAtSign() {
            assertThatThrownBy(() -> Operator.HAS_DOMAIN.extractExpectedValue("not-an-email"))
                .isInstanceOf(AccessForbiddenException.class);
        }

        @Test
        void hasDomainShouldThrowWhenEmailIsEmpty() {
            assertThatThrownBy(() -> Operator.HAS_DOMAIN.extractExpectedValue(""))
                .isInstanceOf(AccessForbiddenException.class);
        }
    }
}
