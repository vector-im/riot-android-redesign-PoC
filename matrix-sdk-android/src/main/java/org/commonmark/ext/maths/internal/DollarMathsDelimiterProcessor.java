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

package org.commonmark.ext.maths.internal;

import org.commonmark.ext.maths.DisplayMaths;
import org.commonmark.ext.maths.InlineMaths;
import org.commonmark.node.Node;
import org.commonmark.node.Text;
import org.commonmark.parser.delimiter.DelimiterProcessor;
import org.commonmark.parser.delimiter.DelimiterRun;

public class DollarMathsDelimiterProcessor implements DelimiterProcessor {
    @Override
    public char getOpeningCharacter() {
        return '$';
    }

    @Override
    public char getClosingCharacter() {
        return '$';
    }

    @Override
    public int getMinLength() {
        return 1;
    }

    @Override
    public int getDelimiterUse(DelimiterRun opener, DelimiterRun closer) {
        if (opener.length() == 1 && closer.length() == 1)
            return 1; // inline
        else if (opener.length() == 2 && closer.length() == 2)
            return 2; // display
        else
            return 0;
    }

    @Override
    public void process(Text opener, Text closer, int delimiterUse) {
        Node maths = delimiterUse == 1 ? new InlineMaths(InlineMaths.InlineDelimiter.SINGLE_DOLLAR) :
                new DisplayMaths(DisplayMaths.DisplayDelimiter.DOUBLE_DOLLAR);

        Node tmp = opener.getNext();
        while (tmp != null && tmp != closer) {
            Node next = tmp.getNext();
            maths.appendChild(tmp);
            tmp = next;
        }

        opener.insertAfter(maths);
    }
}
