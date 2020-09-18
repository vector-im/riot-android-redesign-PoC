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
import org.commonmark.node.Node;
import org.commonmark.node.Text;
import org.commonmark.renderer.html.HtmlNodeRendererContext;
import org.commonmark.renderer.html.HtmlWriter;

import java.util.Collections;
import java.util.Map;

public class MathsHtmlNodeRenderer extends MathsNodeRenderer {
    private final HtmlNodeRendererContext context;
    private final HtmlWriter html;

    public MathsHtmlNodeRenderer(HtmlNodeRendererContext context) {
        this.context = context;
        this.html = context.getWriter();
    }

    @Override
    public void render(Node node) {
        boolean display = node.getClass() == DisplayMaths.class;
        Node contents = node.getFirstChild(); // should be the only child
        String latex = ((Text) contents).getLiteral();
        Map<String, String> attributes = context.extendAttributes(node, display ? "div" : "span", Collections.<String, String>singletonMap("data-mx-maths",
                latex));
        html.tag(display ? "div" : "span", attributes);
        html.tag("code");
        context.render(contents);
        html.tag("/code");
        html.tag(display ? "/div" : "/span");
    }
}
