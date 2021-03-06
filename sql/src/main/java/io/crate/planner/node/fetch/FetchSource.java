/*
 * Licensed to Crate under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.  Crate licenses this file
 * to you under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial
 * agreement.
 */

package io.crate.planner.node.fetch;

import io.crate.metadata.ReferenceInfo;
import io.crate.metadata.TableIdent;
import io.crate.planner.symbol.Field;
import io.crate.planner.symbol.Reference;

import java.util.Collection;
import java.util.List;

public class FetchSource {

    private final List<ReferenceInfo> partitionedByColumns;
    private final Collection<Field> docIdFields;
    private final Collection<Reference> references;
    private final TableIdent tableIdent;
    private final boolean fetchRequired;

    public FetchSource(TableIdent tableIdent,
                       List<ReferenceInfo> partitionedByColumns,
                       Collection<Field> docIdFields,
                       Collection<Reference> references) {
        this.partitionedByColumns = partitionedByColumns;
        this.docIdFields = docIdFields;
        this.references = references;
        this.tableIdent = tableIdent;
        this.fetchRequired = !references.isEmpty();
    }

    public boolean fetchRequired() {
        return fetchRequired;
    }

    public Collection<Field> docIdFields() {
        return docIdFields;
    }

    public List<ReferenceInfo> partitionedByColumns() {
        return partitionedByColumns;
    }

    public Collection<Reference> references() {
        return references;
    }

    public TableIdent tableIdent() {
        return tableIdent;
    }

}
