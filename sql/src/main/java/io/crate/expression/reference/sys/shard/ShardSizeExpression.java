/*
 * Licensed to CRATE Technology GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
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

package io.crate.expression.reference.sys.shard;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import io.crate.expression.NestableInput;
import org.elasticsearch.index.shard.IndexShard;
import org.elasticsearch.index.store.StoreStats;

import java.util.concurrent.TimeUnit;

public class ShardSizeExpression implements NestableInput<Long> {

    private final Supplier<Long> sizeSupplier;

    public ShardSizeExpression(final IndexShard indexShard) {
        sizeSupplier = Suppliers.memoizeWithExpiration(new Supplier<Long>() {
            @Override
            public Long get() {
                StoreStats storeStats = indexShard.storeStats();
                if (storeStats == null) {
                    // will return null if already closed due to shard deletion
                    return 0L;
                }
                return storeStats.getSizeInBytes();
            }
        }, 10, TimeUnit.SECONDS);
    }

    @Override
    public Long value() {
        return sizeSupplier.get();
    }
}
