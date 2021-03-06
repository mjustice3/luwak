package uk.co.flax.luwak.termextractor;

import org.apache.lucene.search.Query;
import uk.co.flax.luwak.termextractor.querytree.QueryTree;

/*
 * Copyright (c) 2013 Lemur Consulting Ltd.
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * Abstract base class for extracting terms from a query or filter.
 *
 * Subclasses should pass in their own types as a parameter to super().
 *
 * @param <T> the type of the Query or Filter to extract terms from
 */
public abstract class QueryTreeBuilder<T extends Query> {

    public final Class<T> cls;

    protected QueryTreeBuilder(Class<T> cls) {
        this.cls = cls;
    }

    public abstract QueryTree buildTree(QueryAnalyzer builder, T query);

}
