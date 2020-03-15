/*
 * Licensed to CRATE Technology GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.metadata.sys;

import static io.crate.types.DataTypes.STRING;
import static io.crate.types.DataTypes.TIMESTAMPZ;

import java.util.List;
import java.util.function.Supplier;

import org.elasticsearch.cluster.node.DiscoveryNode;

import io.crate.expression.reference.sys.job.JobContextLog;
import io.crate.metadata.ColumnIdent;
import io.crate.metadata.RelationName;
import io.crate.metadata.Routing;
import io.crate.metadata.SystemTable;
import io.crate.types.ArrayType;

public class SysJobsLogTableInfo {

    public static final RelationName IDENT = new RelationName(SysSchemaInfo.NAME, "jobs_log");

    public static SystemTable<JobContextLog> create(Supplier<DiscoveryNode> localNode) {
        return SystemTable.<JobContextLog>builder()
            .add("id", STRING, x -> x.id().toString())
            .add("username", STRING, JobContextLog::username)
            .add("stmt", STRING, JobContextLog::statement)
            .add("started", TIMESTAMPZ, JobContextLog::started)
            .add("ended", TIMESTAMPZ, JobContextLog::ended)
            .add("error", STRING, JobContextLog::errorMessage)
            .startObject("classification")
                .add("type", STRING, x -> x.classification().type().name())
                .add("labels", new ArrayType<>(STRING), x -> List.copyOf(x.classification().labels()))
            .endObject()
            .startObject("node")
                .add("id", STRING, ignored -> localNode.get().getId())
                .add("name", STRING, ignored -> localNode.get().getName())
            .endObject()
            .withRouting(nodes -> Routing.forTableOnAllNodes(IDENT, nodes))
            .setPrimaryKeys(new ColumnIdent("id"))
            .build(IDENT);
    }
}
