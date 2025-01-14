/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.index.mapper;

import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexableField;
import org.elasticsearch.common.lucene.Lucene;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentBuilder;

import java.io.IOException;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.elasticsearch.common.xcontent.support.XContentMapValues.nodeBooleanValue;

public class RoutingFieldMapper extends MetadataFieldMapper {

    public static final String NAME = "_routing";
    public static final String CONTENT_TYPE = "_routing";

    public static class Defaults {
        public static final String NAME = "_routing";

        public static final FieldType FIELD_TYPE = new FieldType();

        static {
            FIELD_TYPE.setIndexOptions(IndexOptions.DOCS);
            FIELD_TYPE.setTokenized(false);
            FIELD_TYPE.setStored(true);
            FIELD_TYPE.setOmitNorms(true);
            FIELD_TYPE.freeze();
        }

        public static final boolean REQUIRED = false;
    }

    public static class Builder extends MetadataFieldMapper.Builder<Builder> {

        private boolean required = Defaults.REQUIRED;

        public Builder() {
            super(Defaults.NAME, Defaults.FIELD_TYPE);
        }

        public Builder required(boolean required) {
            this.required = required;
            return builder;
        }

        @Override
        public RoutingFieldMapper build(BuilderContext context) {
            return new RoutingFieldMapper(fieldType, required, context.indexSettings());
        }
    }

    public static class TypeParser implements MetadataFieldMapper.TypeParser {
        @Override
        public MetadataFieldMapper.Builder<?> parse(String name, Map<String, Object> node, ParserContext parserContext) throws MapperParsingException {
            Builder builder = new Builder();
            for (Iterator<Map.Entry<String, Object>> iterator = node.entrySet().iterator(); iterator.hasNext();) {
                Map.Entry<String, Object> entry = iterator.next();
                String fieldName = entry.getKey();
                Object fieldNode = entry.getValue();
                if (fieldName.equals("required")) {
                    builder.required(nodeBooleanValue(fieldNode, name + ".required"));
                    iterator.remove();
                }
            }
            return builder;
        }

        @Override
        public MetadataFieldMapper getDefault(MappedFieldType fieldType, ParserContext context) {
            final Settings indexSettings = context.mapperService().getIndexSettings().getSettings();
            if (fieldType != null) {
                return new RoutingFieldMapper(Defaults.FIELD_TYPE, Defaults.REQUIRED, indexSettings);
            } else {
                return parse(NAME, Collections.emptyMap(), context)
                        .build(new BuilderContext(indexSettings, new ContentPath(1)));
            }
        }
    }

    static final class RoutingFieldType extends StringFieldType {

        static RoutingFieldType INSTANCE = new RoutingFieldType();

        RoutingFieldType() {
            super(NAME, true, false);
            setIndexAnalyzer(Lucene.KEYWORD_ANALYZER);
            setSearchAnalyzer(Lucene.KEYWORD_ANALYZER);
        }

        @Override
        public String typeName() {
            return CONTENT_TYPE;
        }

    }

    private final boolean required;

    private RoutingFieldMapper(FieldType fieldType, boolean required, Settings indexSettings) {
        super(fieldType, RoutingFieldType.INSTANCE, indexSettings);
        this.required = required;
    }

    public boolean required() {
        return this.required;
    }

    @Override
    public void preParse(ParseContext context) throws IOException {
        super.parse(context);
    }

    @Override
    public void parse(ParseContext context) throws IOException {
        // no need ot parse here, we either get the routing in the sourceToParse
        // or we don't have routing, if we get it in sourceToParse, we process it in preParse
        // which will always be called
    }

    @Override
    protected void parseCreateField(ParseContext context, List<IndexableField> fields) throws IOException {
        String routing = context.sourceToParse().routing();
        if (routing != null) {
            if (fieldType.indexOptions() != IndexOptions.NONE || fieldType.stored()) {
                fields.add(new Field(fieldType().name(), routing, fieldType));
                createFieldNamesField(context, fields);
            }
        }
    }

    @Override
    protected String contentType() {
        return CONTENT_TYPE;
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        boolean includeDefaults = params.paramAsBoolean("include_defaults", false);

        // if all are defaults, no sense to write it at all
        if (!includeDefaults && required == Defaults.REQUIRED) {
            return builder;
        }
        builder.startObject(CONTENT_TYPE);
        if (includeDefaults || required != Defaults.REQUIRED) {
            builder.field("required", required);
        }
        builder.endObject();
        return builder;
    }
}
