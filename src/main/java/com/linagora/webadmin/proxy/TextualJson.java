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

import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Reads OIDC values only when they are JSON strings.
 *
 * <p>{@link JsonNode#asText()} turns {@code null} into {@code "null"}, numbers and booleans into their text and
 * containers into {@code ""}, which lets such values match configured strings. Anything that is not a JSON string
 * is reported as absent instead.
 */
final class TextualJson {

    private TextualJson() {
    }

    static Optional<String> textual(JsonNode node) {
        return Optional.ofNullable(node)
            .filter(JsonNode::isTextual)
            .map(JsonNode::textValue);
    }

    static Optional<String> textualField(JsonNode json, String fieldName) {
        return Optional.ofNullable(json)
            .map(object -> object.get(fieldName))
            .flatMap(TextualJson::textual);
    }
}
