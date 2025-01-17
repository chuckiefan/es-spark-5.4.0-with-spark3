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
package org.elasticsearch.hadoop.serialization.dto.mapping;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.elasticsearch.hadoop.EsHadoopIllegalArgumentException;
import org.elasticsearch.hadoop.serialization.FieldType;

@SuppressWarnings("serial")
public class Field implements Serializable {

    private static final Field[] NO_FIELDS = new Field[0];

    private final String name;
    private final FieldType type;
    private final Field[] properties;

    public Field(String name, FieldType type) {
        this(name, type, NO_FIELDS);
    }

    public Field(String name, FieldType type, Collection<Field> properties) {
        this(name, type, (properties != null ? properties.toArray(new Field[properties.size()]) : NO_FIELDS));
    }

    Field(String name, FieldType type, Field[] properties) {
        this.name = name;
        this.type = type;
        this.properties = properties;
    }

    public Field[] properties() { return properties; }

    public FieldType type() {
        return type;
    }

    public String name() { return name; }

    public static Field parseField(Map<String, Object> content) {
        Iterator<Entry<String, Object>> iterator = content.entrySet().iterator();
        return (iterator.hasNext() ? skipHeaders(parseField(iterator.next(), null)) : null);
    }

    private static Field skipHeaders(Field field) {
        Field[] props = field.properties();

        // handle the common case of mapping by removing the first field (mapping.)
        if (props.length > 0 && props[0] != null && "mappings".equals(props[0].name()) && FieldType.OBJECT.equals(props[0].type())) {
            // can't return the type as it is an object of properties
            return props[0].properties()[0];
        }
        return field;
    }

    /**
     * Returns the associated fields with the given mapping. Handles removal of mappings/<type>
     *
     * @param field
     * @return
     */
    public static Map<String, FieldType> toLookupMap(Field field) {
        if (field == null) {
            return Collections.<String, FieldType> emptyMap();
        }

        Map<String, FieldType> map = new LinkedHashMap<String, FieldType>();

        for (Field nestedField : skipHeaders(field).properties()) {
            add(map, nestedField, null);
        }

        return map;
    }

    static void add(Map<String, FieldType> fields, Field field, String parentName) {
        String fieldName = (parentName != null ? parentName + "." + field.name() : field.name());

        fields.put(fieldName, field.type());

        if (FieldType.isCompound(field.type())) {
            for (Field nestedField : field.properties()) {
                add(fields, nestedField, fieldName);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Field parseField(Entry<String, Object> entry, String previousKey) {
        // can be "type" or field name
        String key = entry.getKey();
        Object value = entry.getValue();

        // nested object
        if (value instanceof Map) {
            Map<String, Object> content = (Map<String, Object>) value;
            // default field type for a map
            FieldType fieldType = FieldType.OBJECT;

            // see whether the field was declared
            Object type = content.get("type");
            if (type instanceof String) {
                fieldType = FieldType.parse(type.toString());

                if (FieldType.isRelevant(fieldType)) {
                    // primitive types are handled on the spot
                    // while compound ones are not
                    if (!FieldType.isCompound(fieldType)) {
                        return new Field(key, fieldType);
                    }
                }
                else {
                    return null;
                }
            }

            // compound type - iterate through types
            List<Field> fields = new ArrayList<Field>(content.size());
            for (Entry<String, Object> e : content.entrySet()) {
                if (e.getValue() instanceof Map) {
                    Field fl = parseField(e, key);
                    if (fl != null && fl.type == FieldType.OBJECT && "properties".equals(fl.name) && !isFieldNamedProperties(e.getValue())) {
                        // use the enclosing field (as it might be nested)
                        return new Field(key, fieldType, fl.properties);
                    }
                    if (fl != null) {
                        fields.add(fl);
                    }
                }
            }
            return new Field(key, fieldType, fields);
        }


        throw new EsHadoopIllegalArgumentException("invalid map received " + entry);
    }


    @SuppressWarnings("unchecked")
    private static boolean isFieldNamedProperties(Object fieldValue){
        if(fieldValue instanceof Map){
            Map<String,Object> fieldValueAsMap = ((Map<String, Object>)fieldValue);
            if((fieldValueAsMap.containsKey("type") && fieldValueAsMap.get("type") instanceof String)
                    || (fieldValueAsMap.containsKey("properties") && !isFieldNamedProperties(fieldValueAsMap.get("properties")))) return true;
        }
        return false;
    }

    @Override
    public String toString() {
        return String.format("%s=%s", name, ((type == FieldType.OBJECT || type == FieldType.NESTED) ? Arrays.toString(properties) : type));
    }
}