/*
 * Copyright (c) 2020 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.commonmark.ext.maths;

import org.commonmark.node.CustomNode;
import org.commonmark.node.Delimited;

public class InlineMaths extends CustomNode implements Delimited {
    public enum InlineDelimiter {
        SINGLE_DOLLAR,
        ROUND_BRACKET_ESCAPED
    };

    private InlineDelimiter delimiter;

    public InlineMaths(InlineDelimiter delimiter) {
        this.delimiter = delimiter;
    }

    @Override
    public String getOpeningDelimiter() {
        switch (delimiter) {
            case SINGLE_DOLLAR:
                return "$";
            case ROUND_BRACKET_ESCAPED:
                return "\\(";
        }
        return null;
    }

    @Override
    public String getClosingDelimiter() {
        switch (delimiter) {
            case SINGLE_DOLLAR:
                return "$";
            case ROUND_BRACKET_ESCAPED:
                return "\\)";
        }
        return null;
    }
}
