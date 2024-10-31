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

public class GoOapiCodegenGenerator extends org.openapitools.codegen.languages.GoClientCodegen {
  private static final Logger LOGGER = LoggerFactory.getLogger(GoOapiCodegenGenerator.class);

  // source folder where to write the files
  protected String sourceFolder = "src";
  protected String apiVersion = "1.0.0";
  protected final static String OS_FILE_TYPE = "HttpFile";

  /**
   * Configures a friendly name for the generator.  This will be used by the generator
   * to select the library with the -g flag.
   *
   * @return the friendly name for the generator
   */
  @Override
  public String getName() {
    return "go-oapi-codegen";
  }

  /**
   * Provides an opportunity to inspect and modify operation data before the code is generated.
   */
  @Override
  public OperationsMap postProcessOperationsWithModels(OperationsMap objs, List<ModelMap> allModels) {
    OperationsMap results = super.postProcessOperationsWithModels(objs, allModels);
    return results;
  }

  /**
   * Returns human-friendly help for the generator.  Provide the consumer with help
   * tips, parameters here
   *
   * @return A string value for the help message
   */
  public String getHelp() {
    return "Generates a go-oapi-codegen client library.";
  }

  public GoOapiCodegenGenerator() {
    super();
    typeMapping.put("File", OS_FILE_TYPE);
    typeMapping.put("file", OS_FILE_TYPE);
    typeMapping.put("binary", OS_FILE_TYPE);
    templateDir = "go-oapi-codegen";
    supportsMultipleInheritance = true;
  }

  @Override
  public ModelsMap postProcessModels(ModelsMap objs) {
    ensureVarsAreInAllVars(objs);
    resolveParameterNamingConflicts(objs);
    addUnconstrainedDiscriminatorInheritance(objs);
    objs = super.postProcessModels(objs);
    addAdditionalImports(objs);
    prefixConstNamesWithType(objs);

    return objs;
  }

  /**
   * When creating enums, a const is created for each possible value. This method prefixes the name of the const
   * with the type of the enum to prevent name collisions within the same go module. It also renames the const to
   * PascalCase.
   * @param models - Map of the models to create
   */
  protected void prefixConstNamesWithType(ModelsMap models) {
    for (ModelMap m : models.getModels()) {
        CodegenModel model = m.getModel();

        if (model.isEnum){
            if (model.allowableValues.containsKey("enumVars")) {
                ArrayList<HashMap<String, String>> enumVars;

                try {
                    enumVars = (ArrayList<HashMap<String, String>>) model.allowableValues.get("enumVars");
                } catch (Exception e) {
                    LOGGER.warn("Error reading allowable values for enum {}, incorrect type", model.getName());
                    return;
                }
                
                for (HashMap<String, String> enumVar : enumVars) {
                    // Switch to PascalCase
                    String name = enumVar.get("name");
                    name = StringUtils.replace(name, "_", " ");
                    name = StringUtils.lowerCase(name);
                    String[] nameparts = name.split(" ");
                    
                    // Prefix with type
                    name = model.getName();
                    for (String namePart : nameparts) {
                        name = name + StringUtils.capitalize(namePart);
                    }
                    enumVar.put("name", name);
                }
            }
        }
    }
  }

  protected void resolveParameterNamingConflicts(ModelsMap objs) {
    for (ModelMap m : objs.getModels()) {
        CodegenModel model = m.getModel();
        for (CodegenProperty param : model.allVars) {
            class Local<T> {
                public T value;
            }

            final Local<String> pName = new Local<>();
            pName.value = param.name;

            while (model.allVars.stream().filter(o ->
                    ("Get" + o.name).equals(pName.value)
                    || ("Get" + o.name + "Ok").equals(pName.value)
                    || ("Has" + o.name).equals(pName.value)
                    || ("Set" + o.name).equals(pName.value)).findFirst().isPresent()) {
                pName.value += "_";
            }

            param.name = pName.value;
        }
    }
  }

  protected void ensureVarsAreInAllVars(ModelsMap objs) {
    for (ModelMap m : objs.getModels()) {
        CodegenModel model = m.getModel();
        for(CodegenProperty prop : model.getVars()) {
            if(model.getAllVars().stream().noneMatch(x -> x.name != null && (x == prop || x.name == prop.name))) {
                model.getAllVars().add(model.getAllVars().size(), prop);
            }
        }
    }
  }

  protected void addUnconstrainedDiscriminatorInheritance(ModelsMap objs) {
    for (ModelMap m : objs.getModels()) {
        CodegenModel model = m.getModel();
        if ((model.oneOf == null || model.oneOf.isEmpty()) && model.discriminator != null) {
            Set<String> inheritedModels = new HashSet<>(model.discriminator.getMappedModels().stream()
                    .map(x -> x.getModelName()).collect(Collectors.toList()));

            if (!inheritedModels.contains(model.classname)) {
                model.oneOf = inheritedModels;
            }
        }
    }
  }

  protected void addAdditionalImports(ModelsMap objs) {
    boolean addedTimeImport = objs.getImports().stream().anyMatch(x -> x.values().stream().anyMatch(y -> "time".equals(y)));
    for (ModelMap m : objs.getModels()) {
      CodegenModel model = m.getModel();
      for (CodegenProperty param : model.allVars) {
          if (!addedTimeImport
              && ("time.Time".equals(param.dataType) || ("[]time.Time".equals(param.dataType)))) {
                objs.getImports().add(createMapping("import", "time"));
              addedTimeImport = true;
          }
      }
    }
  }

  @Override
  public void processOpenAPI(OpenAPI openAPI) {
    InlineModelFlattener inlineModelResolver = new InlineModelFlattener();
    inlineModelResolver.setInlineSchemaNameMapping(inlineSchemaNameMapping());
    inlineModelResolver.flatten(openAPI);

    for(Map.Entry<String, Schema> x : openAPI.getComponents().getSchemas().entrySet()) {
      Schema model = x.getValue();

      fixModelFreeform(model);
      if(model instanceof ArraySchema) {
        fixModelFreeform(((ArraySchema)model).getItems());
      }
    }

    super.processOpenAPI(openAPI);
  }

  private void fixModelFreeform(Schema model) {
    if (ModelUtils.isDisallowAdditionalPropertiesIfNotPresent() && ModelUtils.isFreeFormObject(model, openAPI)) {
      Schema addlProps = ModelUtils.getAdditionalProperties(model);
      if (addlProps == null) {
          Map<String, Object> exts = model.getExtensions();
          if(exts == null) {
              exts = new HashMap<>();
              model.setExtensions(exts);
          }
          exts.put("x-is-free-form", false);
      }
    }
  }

  @Override
  public boolean getUseInlineModelResolver() {
    return false;
  }

  @Override
  protected String getParameterDataType(Parameter parameter, Schema schema) {
    Schema unaliasSchema = unaliasSchema(schema, Collections.emptyMap());
    if (unaliasSchema.get$ref() != null) {
        return toModelName(ModelUtils.getSimpleRef(unaliasSchema.get$ref()));
    }
    return null;
  }

  @Override
  public String getTypeDeclaration(Schema p) {
      if (ModelUtils.isArraySchema(p)) {
          ArraySchema ap = (ArraySchema) p;
          Schema inner = ap.getItems();
          // In OAS 3.0.x, the array "items" attribute is required.
          // In OAS >= 3.1, the array "items" attribute is optional such that the OAS
          // specification is aligned with the JSON schema specification.
          // When "items" is not specified, the elements of the array may be anything at all.
          if (inner != null) {
              inner = unaliasSchema(inner, Collections.emptyMap());
          }
          String typDecl;
          if (inner != null) {
              typDecl = getTypeDeclaration(inner);
          } else {
              typDecl = "interface{}";
          }
          if (inner != null && Boolean.TRUE.equals(inner.getNullable())) {
              typDecl = "*" + typDecl;
          }
          return "[]" + typDecl;
      } else if (ModelUtils.isMapSchema(p)) {
          Schema inner = ModelUtils.getAdditionalProperties(p);
          return getSchemaType(p) + "[string]" + getTypeDeclaration(unaliasSchema(inner, Collections.emptyMap()));
      }
      //return super.getTypeDeclaration(p);

      // Not using the supertype invocation, because we want to UpperCamelize
      // the type.
      String openAPIType = getSchemaType(p);
      String ref = p.get$ref();
      if (ref != null && !ref.isEmpty()) {
          String tryRefV2 = "#/definitions/" + openAPIType;
          String tryRefV3 = "#/components/schemas/" + openAPIType;
          if (ref.equals(tryRefV2) || ref.equals(tryRefV3)) {
              return toModelName(openAPIType);
          }
      }

      if (typeMapping.containsKey(openAPIType)) {
          return typeMapping.get(openAPIType);
      }

      if (typeMapping.containsValue(openAPIType)) {
          return openAPIType;
      }

      if (languageSpecificPrimitives.contains(openAPIType)) {
          return openAPIType;
      }

      return toModelName(openAPIType);
  }

  // TODO: consider removing
  // TODO: since it is no longer part of GoClientCodegen code generator, maybe we should remove it
  private Schema unaliasSchema(Schema schema, Map<String, String> importMappings) {
      Map<String, Schema> allSchemas = ModelUtils.getSchemas(openAPI);
        if (allSchemas == null || allSchemas.isEmpty()) {
            return schema;
        }

        if (schema != null && StringUtils.isNotEmpty(schema.get$ref())) {
            String simpleRef = ModelUtils.getSimpleRef(schema.get$ref());
            if (importMappings.containsKey(simpleRef)) {
                return schema;
            }
            Schema ref = allSchemas.get(simpleRef);
            if (ref == null) {
                return schema;
            } else if (ref.getEnum() != null && !ref.getEnum().isEmpty()) {
                // top-level enum class
                return schema;
            } else if (ModelUtils.isArraySchema(ref)) {
                if (ModelUtils.isGenerateAliasAsModel(ref)) {
                    return schema; // generate a model extending array
                } else {
                    return unaliasSchema(allSchemas.get(ModelUtils.getSimpleRef(schema.get$ref())),
                            importMappings);
                }
            } else if (ModelUtils.isComposedSchema(ref)) {
                return schema;
            } else if (ModelUtils.isMapSchema(ref)) {
                if (ref.getProperties() != null && !ref.getProperties().isEmpty()) // has at least one property
                    return schema; // treat it as model
                else {
                    if (ModelUtils.isGenerateAliasAsModel(ref)) {
                        return schema; // generate a model extending map
                    } else {
                        // treat it as a typical map
                        return unaliasSchema(allSchemas.get(ModelUtils.getSimpleRef(schema.get$ref())),
                                importMappings);
                    }
                }
            } else if (ModelUtils.isObjectSchema(ref)) { // model
                if ((ref.getProperties() != null && !ref.getProperties().isEmpty()) || ModelUtils.isDisallowAdditionalPropertiesIfNotPresent() || new Boolean(false).equals(ref.getAdditionalProperties())) { // has at least one property
                  if (ModelUtils.hasSelfReference(openAPI, ref)) {
                        // it's self referencing so returning itself instead
                        return schema;
                    } else {
                        // TODO we may revise below to return `ref` instead of schema
                        // which is the last reference to the actual model/object
                        return schema;
                    }
                } else { // free form object (type: object)
                    return unaliasSchema(allSchemas.get(ModelUtils.getSimpleRef(schema.get$ref())),
                            importMappings);
                }
            } else {
                return unaliasSchema(allSchemas.get(ModelUtils.getSimpleRef(schema.get$ref())), importMappings);
            }
        }
    return schema;
  }

  @Override
  public CodegenModel fromModel(String name, Schema schema) {
      Map<String, Schema> allDefinitions = ModelUtils.getSchemas(this.openAPI);
      if (typeAliases == null) {
          typeAliases = getAllAliases(allDefinitions);
      }
      return super.fromModel(name, schema);
  }

  /**
     * Determine all of the types in the model definitions (schemas) that are aliases of
     * simple types.
     *
     * @param schemas The complete set of model definitions (schemas).
     * @return A mapping from model name to type alias
     */
    private Map<String, String> getAllAliases(Map<String, Schema> schemas) {
      if (schemas == null || schemas.isEmpty()) {
          return new HashMap<>();
      }

      Map<String, String> aliases = new HashMap<>();
      for (Map.Entry<String, Schema> entry : schemas.entrySet()) {
          Schema schema = entry.getValue();
          if (isAliasOfSimpleTypes(schema)) {
              String oasName = entry.getKey();
              String schemaType = getPrimitiveType(schema);
              aliases.put(oasName, schemaType);
          }

      }

      return aliases;
  }

  private static Boolean isAliasOfSimpleTypes(Schema schema) {
    return (!ModelUtils.isObjectSchema(schema)
            && !ModelUtils.isArraySchema(schema)
            && !ModelUtils.isMapSchema(schema)
            && !ModelUtils.isComposedSchema(schema)
            && schema.getEnum() == null);
  }

  @Override
  protected String getSingleSchemaType(Schema schema) {
    Schema unaliasSchema = unaliasSchema(schema, importMapping);

    if (StringUtils.isNotBlank(unaliasSchema.get$ref())) { // reference to another definition/schema
        // get the schema/model name from $ref
        String schemaName = ModelUtils.getSimpleRef(unaliasSchema.get$ref());
        if (StringUtils.isNotEmpty(schemaName)) {
            if (importMapping.containsKey(schemaName)) {
                return schemaName;
            }
            return getAlias(schemaName);
        } else {
            LOGGER.warn("Error obtaining the datatype from ref: {}. Default to 'object'", unaliasSchema.get$ref());
            return "object";
        }
    } else { // primitive type or model
        return getAlias(getPrimitiveType(unaliasSchema));
    }
  }

  /**
   * Return the OAI type (e.g. integer, long, etc) corresponding to a schema.
   * <pre>$ref</pre> is not taken into account by this method.
   * <p>
   * If the schema is free-form (i.e. 'type: object' with no properties) or inline
   * schema, the returned OAI type is 'object'.
   *
   * @param schema
   * @return type
   */
  private String getPrimitiveType(Schema schema) {
    if (schema == null) {
        throw new RuntimeException("schema cannot be null in getPrimitiveType");
    } else if (typeMapping.containsKey(schema.getType() + "+" + schema.getFormat())) {
        // allows custom type_format mapping.
        // use {type}+{format}
        return typeMapping.get(schema.getType() + "+" + schema.getFormat());
    } else if (ModelUtils.isNullType(schema)) {
        // The 'null' type is allowed in OAS 3.1 and above. It is not supported by OAS 3.0.x,
        // though this tooling supports it.
        return "null";
    } else if (ModelUtils.isDecimalSchema(schema)) {
        // special handle of type: string, format: number
        return "decimal";
    } else if (ModelUtils.isByteArraySchema(schema)) {
        return "ByteArray";
    } else if (ModelUtils.isFileSchema(schema)) {
        return "file";
    } else if (ModelUtils.isBinarySchema(schema)) {
        return SchemaTypeUtil.BINARY_FORMAT;
    } else if (ModelUtils.isBooleanSchema(schema)) {
        return SchemaTypeUtil.BOOLEAN_TYPE;
    } else if (ModelUtils.isDateSchema(schema)) {
        return SchemaTypeUtil.DATE_FORMAT;
    } else if (ModelUtils.isDateTimeSchema(schema)) {
        return "DateTime";
    } else if (ModelUtils.isNumberSchema(schema)) {
        if (schema.getFormat() == null) { // no format defined
            return "number";
        } else if (ModelUtils.isFloatSchema(schema)) {
            return SchemaTypeUtil.FLOAT_FORMAT;
        } else if (ModelUtils.isDoubleSchema(schema)) {
            return SchemaTypeUtil.DOUBLE_FORMAT;
        } else {
            LOGGER.warn("Unknown `format` {} detected for type `number`. Defaulting to `number`", schema.getFormat());
            return "number";
        }
    } else if (ModelUtils.isIntegerSchema(schema)) {
        if (ModelUtils.isLongSchema(schema)) {
            return "long";
        } else {
            return schema.getType(); // integer
        }
    } else if (ModelUtils.isMapSchema(schema)) {
        return "map";
    } else if (ModelUtils.isArraySchema(schema)) {
        if (ModelUtils.isSet(schema)) {
            return "set";
        } else {
            return "array";
        }
    } else if (ModelUtils.isUUIDSchema(schema)) {
        return "UUID";
    } else if (ModelUtils.isURISchema(schema)) {
        return "URI";
    } else if (ModelUtils.isStringSchema(schema)) {
        if (typeMapping.containsKey(schema.getFormat())) {
            // If the format matches a typeMapping (supplied with the --typeMappings flag)
            // then treat the format as a primitive type.
            // This allows the typeMapping flag to add a new custom type which can then
            // be used in the format field.
            return schema.getFormat();
        }
        return "string";
    } else if (isFreeFormObject(schema)) {
        // Note: the value of a free-form object cannot be an arbitrary type. Per OAS specification,
        // it must be a map of string to values.
        return "object";
    } else if (schema.getProperties() != null && !schema.getProperties().isEmpty()) { // having property implies it's a model
        return "object";
    } else if ("object".equals(schema.getType())) {
        return "object";
    } else if (ModelUtils.isAnyType(schema)) {
        return "AnyType";
    } else if (StringUtils.isNotEmpty(schema.getType())) {
        if (!importMapping.containsKey(schema.getType())) {
            LOGGER.warn("Unknown type found in the schema: {}", schema.getType());
        }
        return schema.getType();
    }
    // The 'type' attribute has not been set in the OAS schema, which means the value
    // can be an arbitrary type, e.g. integer, string, object, array, number...
    // TODO: we should return a different value to distinguish between free-form object
    // and arbitrary type.
    return "object";
  }

  
  // TODO: consider removing
  // TODO: since it is no longer part of GoClientCodegen code generator, maybe we should remove it
  protected boolean isFreeFormObject(Schema schema) {
    if (schema == null) {
        return false;
    }

    // not free-form if allOf, anyOf, oneOf is not empty
    if (schema instanceof ComposedSchema) {
        ComposedSchema cs = (ComposedSchema) schema;
        List<Schema> interfaces = ModelUtils.getInterfaces(cs);
        if (interfaces != null && !interfaces.isEmpty()) {
            return false;
        }
    }

    // has at least one property
    if ("object".equals(schema.getType())) {
        // no properties
        if ((schema.getProperties() == null || schema.getProperties().isEmpty())) {
            Schema addlProps = ModelUtils.getAdditionalProperties(schema);

            if (schema.getExtensions() != null && schema.getExtensions().containsKey("x-is-free-form")) {
                // User has hard-coded vendor extension to handle free-form evaluation.
                boolean isFreeFormExplicit = Boolean.parseBoolean(String.valueOf(schema.getExtensions().get("x-is-free-form")));
                return isFreeFormExplicit;
            }

            // additionalProperties not defined
            if (addlProps == null) {
                return !ModelUtils.isDisallowAdditionalPropertiesIfNotPresent();
            } else {
                if (addlProps instanceof ObjectSchema) {
                    ObjectSchema objSchema = (ObjectSchema) addlProps;
                    // additionalProperties defined as {}
                    if (objSchema.getProperties() == null || objSchema.getProperties().isEmpty()) {
                        return true;
                    }
                } else if (addlProps instanceof Schema) {
                    // additionalProperties defined as {}
                    if (addlProps.getType() == null && addlProps.get$ref() == null && (addlProps.getProperties() == null || addlProps.getProperties().isEmpty())) {
                        return true;
                    }
                }
            }
        }
    }

    return false;
  }
}