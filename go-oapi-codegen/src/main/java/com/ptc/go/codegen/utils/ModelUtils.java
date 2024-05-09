package com.ptc.go.codegen.utils;

/*
 * Copyright 2018 OpenAPI-Generator Contributors (https://openapi-generator.tech)
 * Copyright 2018 SmartBear Software
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


 import com.fasterxml.jackson.databind.JsonNode;
 import com.fasterxml.jackson.databind.ObjectMapper;
 import io.swagger.v3.core.util.AnnotationsUtils;
 import io.swagger.v3.core.util.Json;
 import io.swagger.v3.oas.models.OpenAPI;
 import io.swagger.v3.oas.models.Operation;
 import io.swagger.v3.oas.models.PathItem;
 import io.swagger.v3.oas.models.callbacks.Callback;
 import io.swagger.v3.oas.models.headers.Header;
 import io.swagger.v3.oas.models.media.*;
 import io.swagger.v3.oas.models.parameters.Parameter;
 import io.swagger.v3.oas.models.parameters.RequestBody;
 import io.swagger.v3.oas.models.responses.ApiResponse;
 import io.swagger.v3.parser.ObjectMapperFactory;
 import io.swagger.v3.parser.core.models.AuthorizationValue;
 import io.swagger.v3.parser.util.ClasspathHelper;
 import io.swagger.v3.parser.util.RemoteUrl;
 import io.swagger.v3.parser.util.SchemaTypeUtil;
 import org.apache.commons.io.FileUtils;
 import org.apache.commons.lang3.StringUtils;
 import org.openapitools.codegen.CodegenConfig;
 import org.openapitools.codegen.CodegenModel;
 import org.openapitools.codegen.IJsonSchemaValidationProperties;
 import org.openapitools.codegen.config.GlobalSettings;
 import org.openapitools.codegen.model.ModelMap;
 import org.openapitools.codegen.model.ModelsMap;
 import org.slf4j.Logger;
 import org.slf4j.LoggerFactory;
 
 import java.io.UnsupportedEncodingException;
 import java.math.BigDecimal;
 import java.net.URI;
 import java.net.URLDecoder;
 import java.nio.file.Files;
 import java.nio.file.Path;
 import java.nio.file.Paths;
 import java.util.*;
 import java.util.Map.Entry;
 import java.util.stream.Collectors;
 
 import static org.openapitools.codegen.utils.OnceLogger.once;
 
 public class ModelUtils extends org.openapitools.codegen.utils.ModelUtils {
    
     /**
      * Has self reference?
      *
      * @param openAPI            OpenAPI spec.
      * @param schema             Schema
      * @param visitedSchemaNames A set of visited schema names
      * @return boolean true if it has at least one self reference
      */
      @Override
     public static boolean hasSelfReference(OpenAPI openAPI,
                                            Schema schema,
                                            Set<String> visitedSchemaNames) {
         if (visitedSchemaNames == null) {
             visitedSchemaNames = new HashSet<String>();
         }
 
         if (schema.get$ref() != null) {
             String ref = getSimpleRef(schema.get$ref());
             if (!visitedSchemaNames.contains(ref)) {
                 visitedSchemaNames.add(ref);
                 Schema referencedSchema = getSchemas(openAPI).get(ref);
                 if (referencedSchema != null) {
                     return hasSelfReference(openAPI, referencedSchema, visitedSchemaNames);
                 } else {
                     LOGGER.error("Failed to obtain schema from `{}` in self reference check", ref);
                     return false;
                 }
             } else {
                 return true;
             }
         }
         if (isComposedSchema(schema)) {
             List<Schema> oneOf = schema.getOneOf();
             if (oneOf != null) {
                 for (Schema s : oneOf) {
                     if (hasSelfReference(openAPI, s, visitedSchemaNames)) {
                         return true;
                     }
                 }
             }
             List<Schema> allOf = schema.getAllOf();
             if (allOf != null) {
                 for (Schema s : allOf) {
                     if (hasSelfReference(openAPI, s, visitedSchemaNames)) {
                         return true;
                     }
                 }
             }
             List<Schema> anyOf = schema.getAnyOf();
             if (anyOf != null) {
                 for (Schema s : anyOf) {
                     if (hasSelfReference(openAPI, s, visitedSchemaNames)) {
                         return true;
                     }
                 }
             }
         } else if (isArraySchema(schema)) {
             Schema itemsSchema = ModelUtils.getSchemaItems(schema);
             if (itemsSchema != null) {
                 return hasSelfReference(openAPI, itemsSchema, visitedSchemaNames);
             }
         } else if (isMapSchema(schema)) {
             Object additionalProperties = schema.getAdditionalProperties();
             if (additionalProperties instanceof Schema) {
                 return hasSelfReference(openAPI, (Schema) additionalProperties, visitedSchemaNames);
             }
         } else if (schema.getNot() != null) {
             return hasSelfReference(openAPI, schema.getNot(), visitedSchemaNames);
         } else if (schema.getProperties() != null && !schema.getProperties().isEmpty()) {
             // go through properties to see if there's any self-reference
             for (Schema property : ((Map<String, Schema>) schema.getProperties()).values()) {
                 if (hasSelfReference(openAPI, property, visitedSchemaNames)) {
                     return true;
                 }
             }
         }
         return false;
     }
 
     /**
      * Get the actual schema from aliases. If the provided schema is not an alias, the schema itself will be returned.
      *
      * @param openAPI specification being checked
      * @param schema  schema (alias or direct reference)
      * @return actual schema
      */
      @Override
     public static Schema unaliasSchema(OpenAPI openAPI,
                                        Schema schema) {
         return unaliasSchema(openAPI, schema, Collections.emptyMap());
     }
 
     /**
      * Get the actual schema from aliases. If the provided schema is not an alias, the schema itself will be returned.
      *
      * @param openAPI        OpenAPI document containing the schemas.
      * @param schema         schema (alias or direct reference)
      * @param schemaMappings mappings of external types to be omitted by unaliasing
      * @return actual schema
      */
      @Override
     public static Schema unaliasSchema(OpenAPI openAPI,
                                        Schema schema,
                                        Map<String, String> schemaMappings) {
         Map<String, Schema> allSchemas = getSchemas(openAPI);
         if (allSchemas == null || allSchemas.isEmpty()) {
             // skip the warning as the spec can have no model defined
             //LOGGER.warn("allSchemas cannot be null/empty in unaliasSchema. Returned 'schema'");
             return schema;
         }
 
         if (schema != null && StringUtils.isNotEmpty(schema.get$ref())) {
             String simpleRef = ModelUtils.getSimpleRef(schema.get$ref());
             if (schemaMappings.containsKey(simpleRef)) {
                 LOGGER.debug("Schema unaliasing of {} omitted because aliased class is to be mapped to {}", simpleRef, schemaMappings.get(simpleRef));
                 return schema;
             }
             Schema ref = allSchemas.get(simpleRef);
             if (ref == null) {
                 if (!isRefToSchemaWithProperties(schema.get$ref())) {
                     once(LOGGER).warn("{} is not defined", schema.get$ref());
                 }
                 return schema;
             } else if (ref.getEnum() != null && !ref.getEnum().isEmpty()) {
                 // top-level enum class
                 return schema;
             } else if (isArraySchema(ref)) {
                 if (isGenerateAliasAsModel(ref)) {
                     return schema; // generate a model extending array
                 } else {
                     return unaliasSchema(openAPI, allSchemas.get(ModelUtils.getSimpleRef(schema.get$ref())),
                             schemaMappings);
                 }
             } else if (isComposedSchema(ref)) {
                 return schema;
             } else if (isMapSchema(ref)) {
                 if (ref.getProperties() != null && !ref.getProperties().isEmpty()) // has at least one property
                     return schema; // treat it as model
                 else {
                     if (isGenerateAliasAsModel(ref)) {
                         return schema; // generate a model extending map
                     } else {
                         // treat it as a typical map
                         return unaliasSchema(openAPI, allSchemas.get(ModelUtils.getSimpleRef(schema.get$ref())),
                                 schemaMappings);
                     }
                 }
             } else if (isObjectSchema(ref)) { // model
                 if (ref.getProperties() != null && !ref.getProperties().isEmpty()) { // has at least one property
                     // TODO we may need to check `hasSelfReference(openAPI, ref)` as a special/edge case:
                     // TODO we may also need to revise below to return `ref` instead of schema
                     // which is the last reference to the actual model/object
                     return schema;
                 } else { // free form object (type: object)
                     return unaliasSchema(openAPI, allSchemas.get(ModelUtils.getSimpleRef(schema.get$ref())),
                             schemaMappings);
                 }
             } else {
                 return unaliasSchema(openAPI, allSchemas.get(ModelUtils.getSimpleRef(schema.get$ref())), schemaMappings);
             }
         }
         return schema;
     }
 
     /**
      * Returns the additionalProperties Schema for the specified input schema.
      * <p>
      * The additionalProperties keyword is used to control the handling of additional, undeclared
      * properties, that is, properties whose names are not listed in the properties keyword.
      * The additionalProperties keyword may be either a boolean or an object.
      * If additionalProperties is a boolean and set to false, no additional properties are allowed.
      * By default when the additionalProperties keyword is not specified in the input schema,
      * any additional properties are allowed. This is equivalent to setting additionalProperties
      * to the boolean value True or setting additionalProperties: {}
      *
      * @param schema  the input schema that may or may not have the additionalProperties keyword.
      * @return the Schema of the additionalProperties. The null value is returned if no additional
      * properties are allowed.
      */
      @Override
     public static Schema getAdditionalProperties(Schema schema) {
         Object addProps = schema.getAdditionalProperties();
         if (addProps instanceof Schema) {
             return (Schema) addProps;
         }
         if (addProps == null) {
             // When reaching this code path, this should indicate the 'additionalProperties' keyword is
             // not present in the OAS schema. This is true for OAS 3.0 documents.
             // However, the parsing logic is broken for OAS 2.0 documents because of the
             // https://github.com/swagger-api/swagger-parser/issues/1369 issue.
             // When OAS 2.0 documents are parsed, the swagger-v2-converter ignores the 'additionalProperties'
             // keyword if the value is boolean. That means codegen is unable to determine whether
             // additional properties are allowed or not.
             //
             // The original behavior was to assume additionalProperties had been set to false.
             if (isDisallowAdditionalPropertiesIfNotPresent()) {
                 // If the 'additionalProperties' keyword is not present in a OAS schema,
                 // interpret as if the 'additionalProperties' keyword had been set to false.
                 // This is NOT compliant with the JSON schema specification. It is the original
                 // 'openapi-generator' behavior.
                 return null;
             }
             /*
             // The disallowAdditionalPropertiesIfNotPresent CLI option has been set to true,
             // but for now that only works with OAS 3.0 documents.
             // The new behavior does not work with OAS 2.0 documents.
             if (extensions == null || !extensions.containsKey(EXTENSION_OPENAPI_DOC_VERSION)) {
                 // Fallback to the legacy behavior.
                 return null;
             }
             // Get original swagger version from OAS extension.
             // Note openAPI.getOpenapi() is always set to 3.x even when the document
             // is converted from a OAS/Swagger 2.0 document.
             // https://github.com/swagger-api/swagger-parser/pull/1374
             SemVer version = new SemVer((String)extensions.get(EXTENSION_OPENAPI_DOC_VERSION));
             if (version.major != 3) {
                 return null;
             }
             */
         }
         if (addProps == null || (addProps instanceof Boolean && (Boolean) addProps)) {
             // Return an empty schema as the properties can take on any type per
             // the spec. See
             // https://github.com/OpenAPITools/openapi-generator/issues/9282 for
             // more details.
             return new Schema();
         }
         return null;
     }
     @Override
     public static Header getReferencedHeader(OpenAPI openAPI, Header header) {
         if (header != null && StringUtils.isNotEmpty(header.get$ref())) {
             String name = getSimpleRef(header.get$ref());
             Header referencedheader = getHeader(openAPI, name);
             if (referencedheader != null) {
                 return referencedheader;
             }
         }
         return header;
     }
     @Override
     public static Header getHeader(OpenAPI openAPI, String name) {
         if (name != null && openAPI != null && openAPI.getComponents() != null && openAPI.getComponents().getHeaders() != null) {
             return openAPI.getComponents().getHeaders().get(name);
         }
         return null;
     }
     @Override
     public static Map<String, List<String>> getChildrenMap(OpenAPI openAPI) {
         Map<String, Schema> allSchemas = getSchemas(openAPI);
 
         Map<String, List<Entry<String, Schema>>> groupedByParent = allSchemas.entrySet().stream()
                 .filter(entry -> isComposedSchema(entry.getValue()))
                 .filter(entry -> getParentName((Schema) entry.getValue(), allSchemas) != null)
                 .collect(Collectors.groupingBy(entry -> getParentName((Schema) entry.getValue(), allSchemas)));
 
         return groupedByParent.entrySet().stream()
                 .collect(Collectors.toMap(entry -> entry.getKey(), entry -> entry.getValue().stream().map(e -> e.getKey()).collect(Collectors.toList())));
     }
 
 }
 