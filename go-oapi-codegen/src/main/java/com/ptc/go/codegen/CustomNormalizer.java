package com.ptc.go.codegen;

import org.apache.commons.lang3.StringUtils;
import org.openapitools.codegen.*;
import org.openapitools.codegen.model.*;
import org.openapitools.codegen.utils.*;
import org.slf4j.*;

import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.parameters.*;
import io.swagger.v3.parser.util.*;

import java.util.*;
import java.util.stream.*;

public class CustomNormalizer extends org.openapitools.codegen.OpenAPINormalizer  {
    private static final Logger LOGGER = LoggerFactory.getLogger(CustomNormalizer.class);
    private OpenAPI openAPI;

    Set<String> ruleNames = new TreeSet<>();
    Set<String> rulesDefaultToTrue = new TreeSet<>();

    // ============= a list of rules =============
    // when set to true, all rules (true or false) are enabled
    final String ENABLE_ALL = "ENABLE_ALL";
    boolean enableAll;

    // when set to true, all rules (true or false) are disabled
    final String DISABLE_ALL = "DISABLE_ALL";
    boolean disableAll;

    // when set to true, $ref in allOf is treated as parent so that x-parent: true will be added
    // to the schema in $ref (if x-parent is not present)
    final String REF_AS_PARENT_IN_ALLOF = "REF_AS_PARENT_IN_ALLOF";

    // when set to true, only keep the first tag in operation if there are more than one tag defined.
    final String KEEP_ONLY_FIRST_TAG_IN_OPERATION = "KEEP_ONLY_FIRST_TAG_IN_OPERATION";

    // when set to true, complex composed schemas (a mix of oneOf/anyOf/anyOf and properties) with
    // oneOf/anyOf containing only `required` and no properties (these are properties inter-dependency rules)
    // are removed as most generators cannot handle such case at the moment
    final String REMOVE_ANYOF_ONEOF_AND_KEEP_PROPERTIES_ONLY = "REMOVE_ANYOF_ONEOF_AND_KEEP_PROPERTIES_ONLY";

    // when set to true, oneOf/anyOf with either string or enum string as sub schemas will be simplified
    // to just string
    final String SIMPLIFY_ANYOF_STRING_AND_ENUM_STRING = "SIMPLIFY_ANYOF_STRING_AND_ENUM_STRING";

    // when set to true, oneOf/anyOf schema with only one sub-schema is simplified to just the sub-schema
    // and if sub-schema contains "null", remove it and set nullable to true instead
    // and if sub-schema contains enum of "null", remove it and set nullable to true instead
    final String SIMPLIFY_ONEOF_ANYOF = "SIMPLIFY_ONEOF_ANYOF";

    // when set to true, boolean enum will be converted to just boolean
    final String SIMPLIFY_BOOLEAN_ENUM = "SIMPLIFY_BOOLEAN_ENUM";

    // when set to a string value, tags in all operations will be reset to the string value provided
    final String SET_TAGS_FOR_ALL_OPERATIONS = "SET_TAGS_FOR_ALL_OPERATIONS";
    String setTagsForAllOperations;

    // when set to true, tags in all operations will be set to operationId or "default" if operationId
    // is empty
    final String SET_TAGS_TO_OPERATIONID = "SET_TAGS_TO_OPERATIONID";
    String setTagsToOperationId;

    // when set to true, auto fix integer with maximum value 4294967295 (2^32-1) or long with 18446744073709551615 (2^64-1)
    // by adding x-unsigned to the schema
    final String ADD_UNSIGNED_TO_INTEGER_WITH_INVALID_MAX_VALUE = "ADD_UNSIGNED_TO_INTEGER_WITH_INVALID_MAX_VALUE";

    // when set to true, refactor schema with allOf and properties in the same level to a schema with allOf only and
    // the allOf contains a new schema containing the properties in the top level
    final String REFACTOR_ALLOF_WITH_PROPERTIES_ONLY = "REFACTOR_ALLOF_WITH_PROPERTIES_ONLY";

    // when set to true, normalize OpenAPI 3.1 spec to make it work with the generator
    final String NORMALIZE_31SPEC = "NORMALIZE_31SPEC";

    // when set to true, remove x-internal: true from models, operations
    final String REMOVE_X_INTERNAL = "REMOVE_X_INTERNAL";
    final String X_INTERNAL = "x-internal";
    boolean removeXInternal;

    // when set (e.g. operationId:getPetById, addPet), filter out (or remove) everything else
    final String FILTER = "FILTER";
    HashSet<String> operationIdFilters = new HashSet<>();
  
    public CustomNormalizer(OpenAPI openAPI, Map<String, String> inputRules) {
        super(openAPI, inputRules);
    }
    @Override
    public Schema normalizeSchema(Schema schema, Set<Schema> visitedSchemas) {
        if (schema == null) {
            return schema;
        }

        if (StringUtils.isNotEmpty(schema.get$ref())) {
            // not need to process $ref
            return schema;
        }

        if ((visitedSchemas.contains(schema))) {
            return schema; // skip due to circular reference
        } else {
            visitedSchemas.add(schema);
        }

        if (schema instanceof ArraySchema) { // array
            normalizeSchema(schema.getItems(), visitedSchemas);
        } else if (schema.getAdditionalProperties() instanceof Schema) { // map
            normalizeSchema((Schema) schema.getAdditionalProperties(), visitedSchemas);
        } else if (ModelUtils.isOneOf(schema)) { // oneOf
            return normalizeOneOf(schema, visitedSchemas);
        } else if (ModelUtils.isAnyOf(schema)) { // anyOf
            return normalizeAnyOf(schema, visitedSchemas);
        } else if (ModelUtils.isAllOfWithProperties(schema)) { // allOf with properties
            schema = normalizeAllOfWithProperties(schema, visitedSchemas);
            normalizeSchema(schema, visitedSchemas);
        } else if (ModelUtils.isAllOf(schema)) { // allOf
            return normalizeAllOf(schema, visitedSchemas);
        } else if (ModelUtils.isComposedSchema(schema)) { // composed schema
            if (ModelUtils.isComplexComposedSchema(schema)) {
                schema = normalizeComplexComposedSchema(schema, visitedSchemas);
            }

            if (schema.getAllOf() != null && !schema.getAllOf().isEmpty()) {
                return normalizeAllOf(schema, visitedSchemas);
            }

            if (schema.getOneOf() != null && !schema.getOneOf().isEmpty()) {
                return normalizeOneOf(schema, visitedSchemas);
            }

            if (schema.getAnyOf() != null && !schema.getAnyOf().isEmpty()) {
                return normalizeAnyOf(schema, visitedSchemas);
            }

            if (schema.getProperties() != null && !schema.getProperties().isEmpty()) {
                normalizeProperties(schema.getProperties(), visitedSchemas);
            }

            if (schema.getAdditionalProperties() != null) {
                // normalizeAdditionalProperties(m);
            }

            return schema;
        } else if (schema.getProperties() != null && !schema.getProperties().isEmpty()) {
            normalizeProperties(schema.getProperties(), visitedSchemas);
        } else if (schema instanceof BooleanSchema) {
            normalizeBooleanSchema(schema, visitedSchemas);
        } else if (schema instanceof IntegerSchema) {
            normalizeIntegerSchema(schema, visitedSchemas);
        } else if (schema instanceof Schema) {
            return normalizeSimpleSchema(schema, visitedSchemas);
        } else {
            throw new RuntimeException("Unknown schema type found in normalizer: " + schema);
        }

        return schema;
    }
    private Schema normalizeOneOf(Schema schema, Set<Schema> visitedSchemas) {
        for (Object item : schema.getOneOf()) {
            if (item == null) {
                continue;
            }
            if (!(item instanceof Schema)) {
                throw new RuntimeException("Error! oneOf schema is not of the type Schema: " + item);
            }
            // normalize oenOf sub schemas one by one
            normalizeSchema((Schema) item, visitedSchemas);
        }
        // process rules here
        schema = processSimplifyOneOf(schema);

        return schema;
    }

    private Schema normalizeAnyOf(Schema schema, Set<Schema> visitedSchemas) {
        for (Object item : schema.getAnyOf()) {
            if (item == null) {
                continue;
            }

            if (!(item instanceof Schema)) {
                throw new RuntimeException("Error! anyOf schema is not of the type Schema: " + item);
            }
            // normalize anyOf sub schemas one by one
            normalizeSchema((Schema) item, visitedSchemas);
        }

        // process rules here
        schema = processSimplifyAnyOf(schema);

        // last rule to process as the schema may become String schema (not "anyOf") after the completion
        return processSimplifyAnyOfStringAndEnumString(schema);
    }
    private Schema normalizeComplexComposedSchema(Schema schema, Set<Schema> visitedSchemas) {
        // loop through properties, if any
        if (schema.getProperties() != null && !schema.getProperties().isEmpty()) {
            normalizeProperties(schema.getProperties(), visitedSchemas);
        }

        processRemoveAnyOfOneOfAndKeepPropertiesOnly(schema);

        return schema;
    }
    private Schema normalizeSimpleSchema(Schema schema, Set<Schema> visitedSchemas) {
        return processNormalize31Spec(schema, visitedSchemas);
    }

    private void normalizeBooleanSchema(Schema schema, Set<Schema> visitedSchemas) {
        processSimplifyBooleanEnum(schema);
    }

    private void normalizeIntegerSchema(Schema schema, Set<Schema> visitedSchemas) {
        processAddUnsignedToIntegerWithInvalidMaxValue(schema);
    }

    private void normalizeProperties(Map<String, Schema> properties, Set<Schema> visitedSchemas) {
        if (properties == null) {
            return;
        }
        for (Map.Entry<String, Schema> propertiesEntry : properties.entrySet()) {
            Schema property = propertiesEntry.getValue();
            Schema newProperty = normalizeSchema(property, visitedSchemas);
            propertiesEntry.setValue(newProperty);
        }
    }

    private Schema normalizeAllOf(Schema schema, Set<Schema> visitedSchemas) {
        for (Object item : schema.getAllOf()) {
            if (!(item instanceof Schema)) {
                throw new RuntimeException("Error! allOf schema is not of the type Schema: " + item);
            }
            // normalize allOf sub schemas one by one
            normalizeSchema((Schema) item, visitedSchemas);
        }
        // process rules here
        processUseAllOfRefAsParent(schema);

        return schema;
    }

    private Schema normalizeAllOfWithProperties(Schema schema, Set<Schema> visitedSchemas) {
        for (Object item : schema.getAllOf()) {
            if (!(item instanceof Schema)) {
                throw new RuntimeException("Error! allOf schema is not of the type Schema: " + item);
            }
            // normalize allOf sub schemas one by one
            normalizeSchema((Schema) item, visitedSchemas);
        }
        // process rules here
        schema = processRefactorAllOfWithPropertiesOnly(schema);

        return schema;
    }

    /**
     * If the schema is oneOf and the sub-schemas is null, set `nullable: true`
     * instead.
     * If there's only one sub-schema, simply return the sub-schema directly.
     *
     * @param schema Schema
     * @return Schema
     */
    private Schema processSimplifyOneOf(Schema schema) {
        if (!getRule(SIMPLIFY_ONEOF_ANYOF)) {
            return schema;
        }

        List<Schema> oneOfSchemas = schema.getOneOf();
        if (oneOfSchemas != null) {
            if (oneOfSchemas.removeIf(oneOf -> isNullTypeSchema(oneOf))) {
                schema.setNullable(true);

                // if only one element left, simplify to just the element (schema)
                if (oneOfSchemas.size() == 1) {
                    if (Boolean.TRUE.equals(schema.getNullable())) { // retain nullable setting
                        ((Schema) oneOfSchemas.get(0)).setNullable(true);
                    }
                    return (Schema) oneOfSchemas.get(0);
                }
            }
        }

        return schema;
    }

    /**
     * If the schema is anyOf and the sub-schemas is null, set `nullable: true` instead.
     * If there's only one sub-schema, simply return the sub-schema directly.
     *
     * @param schema Schema
     * @return Schema
     */
    private Schema processSimplifyAnyOf(Schema schema) {
        if (!getRule(SIMPLIFY_ONEOF_ANYOF)) {
            return schema;
        }

        List<Schema> anyOfSchemas = schema.getAnyOf();
        if (anyOfSchemas != null) {
            if (anyOfSchemas.removeIf(anyOf -> isNullTypeSchema(anyOf))) {
                schema.setNullable(true);
            }

            // if only one element left, simplify to just the element (schema)
            if (anyOfSchemas.size() == 1) {
                if (Boolean.TRUE.equals(schema.getNullable())) { // retain nullable setting
                    ((Schema) anyOfSchemas.get(0)).setNullable(true);
                }
                return (Schema) anyOfSchemas.get(0);
            }
        }

        return schema;
    }

    /**
     * If the schema is boolean and its enum is defined,
     * then simply it to just boolean.
     *
     * @param schema Schema
     * @return Schema
     */
    private void processSimplifyBooleanEnum(Schema schema) {
        if (!getRule(SIMPLIFY_BOOLEAN_ENUM)) {
            return;
        }

        if (schema instanceof BooleanSchema) {
            BooleanSchema bs = (BooleanSchema) schema;
            if (bs.getEnum() != null && !bs.getEnum().isEmpty()) { // enum defined
                bs.setEnum(null);
            }
        }
    }

    /**
     * If the schema is integer and the max value is invalid (out of bound)
     * then add x-unsigned to use unsigned integer/long instead.
     *
     * @param schema Schema
     * @return Schema
     */
    private void processAddUnsignedToIntegerWithInvalidMaxValue(Schema schema) {
        if (!getRule(ADD_UNSIGNED_TO_INTEGER_WITH_INVALID_MAX_VALUE)) {
            return;
        }

        if (schema instanceof IntegerSchema) {
            if (ModelUtils.isLongSchema(schema)) {
                if ("18446744073709551615".equals(String.valueOf(schema.getMaximum())) &&
                        "0".equals(String.valueOf(schema.getMinimum()))) {
                    schema.addExtension("x-unsigned", true);
                }
            } else {
                if ("4294967295".equals(String.valueOf(schema.getMaximum())) &&
                        "0".equals(String.valueOf(schema.getMinimum()))) {
                    schema.addExtension("x-unsigned", true);
                }
            }
        }
    }

    // ===================== a list of rules =====================
    // all rules (fuctions) start with the word "process"

    /**
     * Child schemas in `allOf` is considered a parent if it's a `$ref` (instead of inline schema).
     *
     * @param schema Schema
     */
    private void processUseAllOfRefAsParent(Schema schema) {
        if (!getRule(REF_AS_PARENT_IN_ALLOF)) {
            return;
        }

        if (schema.getAllOf() == null) {
            return;
        }

        if (schema.getAllOf().size() == 1) {
            return;
        }

        for (Object item : schema.getAllOf()) {
            if (!(item instanceof Schema)) {
                throw new RuntimeException("Error! allOf schema is not of the type Schema: " + item);
            }
            Schema s = (Schema) item;

            if (StringUtils.isNotEmpty(s.get$ref())) {
                String ref = ModelUtils.getSimpleRef(s.get$ref());
                // TODO need to check for requestBodies?
                Schema refSchema = openAPI.getComponents().getSchemas().get(ref);
                if (refSchema == null) {
                    throw new RuntimeException("schema cannot be null with ref " + ref);
                }
                if (refSchema.getExtensions() == null) {
                    refSchema.setExtensions(new HashMap<>());
                }

                if (refSchema.getExtensions().containsKey("x-parent")) {
                    // doing nothing as x-parent already exists
                } else {
                    refSchema.getExtensions().put("x-parent", true);
                }

                LOGGER.debug("processUseAllOfRefAsParent added `x-parent: true` to {}", refSchema);
            }
        }
    }

    /**
     * When set to true, refactor schema with allOf and properties in the same level to a schema with allOf only and
     * the allOf contains a new schema containing the properties in the top level.
     *
     * @param schema Schema
     * @return Schema
     */
    private Schema processRefactorAllOfWithPropertiesOnly(Schema schema) {
        if (!getRule(REFACTOR_ALLOF_WITH_PROPERTIES_ONLY)) {
            return schema;
        }

        ObjectSchema os = new ObjectSchema();
        // set the properties, etc of the new schema to the properties of schema
        os.setProperties(schema.getProperties());
        os.setRequired(schema.getRequired());
        os.setAdditionalProperties(schema.getAdditionalProperties());
        os.setNullable(schema.getNullable());
        os.setDescription(schema.getDescription());
        os.setDeprecated(schema.getDeprecated());
        os.setExample(schema.getExample());
        os.setExamples(schema.getExamples());
        os.setTitle(schema.getTitle());
        schema.getAllOf().add(os); // move new schema as a child schema of allOf
        // clean up by removing properties, etc
        schema.setProperties(null);
        schema.setRequired(null);
        schema.setAdditionalProperties(null);
        schema.setNullable(null);
        schema.setDescription(null);
        schema.setDeprecated(null);
        schema.setExample(null);
        schema.setExamples(null);
        schema.setTitle(null);

        // at this point the schema becomes a simple allOf (no properties) with an additional schema containing
        // the properties

        return schema;
    }

    /**
     * When set to true, normalize schema so that it works well with the generator.
     *
     * @param schema         Schema
     * @param visitedSchemas a set of visited schemas
     * @return Schema
     */
    private Schema processNormalize31Spec(Schema schema, Set<Schema> visitedSchemas) {
        if (!getRule(NORMALIZE_31SPEC)) {
            return schema;
        }

        if (schema == null) {
            return null;
        }

        if (schema instanceof JsonSchema &&
                schema.get$schema() == null &&
                schema.getTypes() == null && schema.getType() == null) {
            // convert any type in v3.1 to empty schema (any type in v3.0 spec), any type example:
            // components:
            //  schemas:
            //    any_type: {}
            return new Schema();
        }

        // return schema if nothing in 3.1 spec types to normalize
        if (schema.getTypes() == null) {
            return schema;
        }

        // process null
        if (schema.getTypes().contains("null")) {
            schema.setNullable(true);
            schema.getTypes().remove("null");
        }

        // only one item (type) left
        if (schema.getTypes().size() == 1) {
            String type = String.valueOf(schema.getTypes().iterator().next());
            if ("array".equals(type)) {
                ArraySchema as = new ArraySchema();
                as.setDescription(schema.getDescription());
                as.setDefault(schema.getDefault());
                if (schema.getExample() != null) {
                    as.setExample(schema.getExample());
                }
                if (schema.getExamples() != null) {
                    as.setExamples(schema.getExamples());
                }
                as.setMinItems(schema.getMinItems());
                as.setMaxItems(schema.getMaxItems());
                as.setExtensions(schema.getExtensions());
                as.setXml(schema.getXml());
                if (schema.getItems() != null) {
                    // `items` is also a json schema
                    if (StringUtils.isNotEmpty(schema.getItems().get$ref())) {
                        Schema ref = new Schema();
                        ref.set$ref(schema.getItems().get$ref());
                        as.setItems(ref);
                    } else { // inline schema (e.g. model, string, etc)
                        Schema updatedItems = normalizeSchema(schema.getItems(), visitedSchemas);
                        as.setItems(updatedItems);
                    }
                }

                return as;
            } else { // other primitive type such as string
                // set type (3.0 spec) directly
                schema.setType(type);
            }
        } else { // more than 1 item
            // convert to anyOf and keep all other attributes (e.g. nullable, description)
            // the same. No need to handle null as it should have been removed at this point.
            for (Object type : schema.getTypes()) {
                switch (String.valueOf(type)) {
                    case "string":
                        schema.addAnyOfItem(new StringSchema());
                        break;
                    case "integer":
                        schema.addAnyOfItem(new IntegerSchema());
                        break;
                    case "number":
                        schema.addAnyOfItem(new NumberSchema());
                        break;
                    case "boolean":
                        schema.addAnyOfItem(new BooleanSchema());
                        break;
                    default:
                        LOGGER.error("Type {} not yet supported in openapi-normalizer to process OpenAPI 3.1 spec with multiple types.");
                        LOGGER.error("Please report the issue via https://github.com/OpenAPITools/openapi-generator/issues/new/.");
                }
            }
        }

        return schema;
    }
    /**
     * If the schema contains anyOf/oneOf and properties, remove oneOf/anyOf as these serve as rules to
     * ensure inter-dependency between properties. It's a workaround as such validation is not supported at the moment.
     *
     * @param schema Schema
     */
    private void processRemoveAnyOfOneOfAndKeepPropertiesOnly(Schema schema) {
        if (!getRule(REMOVE_ANYOF_ONEOF_AND_KEEP_PROPERTIES_ONLY)) {
            return;
        }

        if (((schema.getOneOf() != null && !schema.getOneOf().isEmpty())
                || (schema.getAnyOf() != null && !schema.getAnyOf().isEmpty())) // has anyOf or oneOf
                && (schema.getProperties() != null && !schema.getProperties().isEmpty()) // has properties
                && schema.getAllOf() == null) { // not allOf
            // clear oneOf, anyOf
            schema.setOneOf(null);
            schema.setAnyOf(null);
        }
    }

    /**
     * If the schema is anyOf and the sub-schemas are either string or enum of string,
     * then simplify it to just enum of string as many generators do not yet support anyOf.
     *
     * @param schema Schema
     * @return Schema
     */
    private Schema processSimplifyAnyOfStringAndEnumString(Schema schema) {
        if (!getRule(SIMPLIFY_ANYOF_STRING_AND_ENUM_STRING)) {
            return schema;
        }

        if (schema.getAnyOf() == null) {
            // ComposedSchema, Schema with `type: null`
            return schema;
        }

        Schema result = null, s0 = null, s1 = null;
        if (schema.getAnyOf().size() == 2) {
            s0 = ModelUtils.unaliasSchema(openAPI, (Schema) schema.getAnyOf().get(0));
            s1 = ModelUtils.unaliasSchema(openAPI, (Schema) schema.getAnyOf().get(1));
        } else {
            return schema;
        }

        s0 = ModelUtils.getReferencedSchema(openAPI, s0);
        s1 = ModelUtils.getReferencedSchema(openAPI, s1);

        // find the string schema (enum)
        if (s0 instanceof StringSchema && s1 instanceof StringSchema) {
            if (((StringSchema) s0).getEnum() != null) { // s0 is enum, s1 is string
                result = (StringSchema) s0;
            } else if (((StringSchema) s1).getEnum() != null) { // s1 is enum, s0 is string
                result = (StringSchema) s1;
            } else { // both are string
                result = schema;
            }
        } else {
            result = schema;
        }

        // set nullable
        if (schema.getNullable() != null) {
            result.setNullable(schema.getNullable());
        }

        // set default
        if (schema.getDefault() != null) {
            result.setDefault(schema.getDefault());
        }

        return result;
    }

    // ===================== end of rules =====================
}
