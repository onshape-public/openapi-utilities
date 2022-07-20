# Custom OpenAPI Go Generator for Onshape Bindings

## Overview
This is a custom Go generator designed to work with [OpenAPI generator](https://github.com/OpenAPITools/openapi-generator).
It implements additional behavior on top of the standard Go generator to better support inheritance, polymorphism, and other Java idioms.
The generator was designed mainly for use with the Onshape REST API, which contains a number of these patterns, but can be used with any OpenAPI specification.

Specifically, this generator does several things differently than the standard Go generator:

- File streams are typed as `*os.File` rather than `**os.File`, eliminating redundant double pointers.
- Struct types with "allOf" tags now include inherited properties from all parent types.
- Free-form objects, or untyped `map[string]interface{}` types, are only generated when a type has no properties and is explicitly marked `"additionalProperties": true`.
Objects without the additional properties tag are assumed not to be free-form, and always have models generated.
- Field getter-setter naming conflicts are resolved automatically by the generator, which adds underscores to conflicting names until everything is unique.
- Schemas with discriminators, but no explicit composition, are treated as enum-like `oneOf` types, allowing for polymorphism. These schemas generate with
getters and setters that allow for modifying base class fields without casting to a concrete derived type.

## Why a custom generator

These changes were originally incorporated into a fork of OpenAPI generator. However, some of these changes are breaking with respect to the Go generator's previous behavior.
Further, this generator's treatment of free-form objects is not necessarily in accordance with the OpenAPI standard. As such, it is unlikely that a PR in the OpenAPI generator repository to
merge these changes would be accepted quickly (especially as there are over 300 other PRs currently open). Therefore, a custom generator is a more lightweight and maintainable
approach to improving OpenAPI generator. By using a custom generator, we are no longer forced to maintain our own fork of OpenAPI. We can take advantage of the latest versions
and features, since custom generators are not tied to a specific OpenAPI version. In addition, it will be much simpler to modify, build, and deploy this custom generator should
we need to modify it in the future.

## Running the generator

After being added to the Java classpath, the generator can be invoked like any other language's OpenAPI generator. The generator's name is `go-oapi-codegen`. For example:

`java -cp "/go-oapi-codegen-1.0.0.jar;/openapi-generator-cli-6.0.1.jar" org.openapitools.codegen.OpenAPIGenerator generate -i .\openapi.json.tmp -g go-oapi-codegen -o .\onshape -c ./openapi_config.json`

This process may vary slightly depending upon the operating system. See the [OpenAPI generator documentation](https://openapi-generator.tech/docs/customization/#use-your-new-generator-with-the-cli) for more details.

## Building the generator

To build the generator, Apache Maven must first be installed. Then, run `mvn package`. This will build the custom generator's JAR file and place it in the `target` directory.

## Debugging the generator

```
# The following debug options are available for all codegen targets:
# -DdebugOpenAPI prints the OpenAPI Specification as interpreted by the codegen
# -DdebugModels prints models passed to the template engine
# -DdebugOperations prints operations passed to the template engine
# -DdebugSupportingFiles prints additional data passed to the template engine

java -DdebugOperations -cp /path/to/openapi-generator-cli.jar:/path/to/your.jar org.openapitools.codegen.OpenAPIGenerator generate -g go-oapi-codegen -i /path/to/openapi.yaml -o ./test
```

Will, for example, output the debug info for operations.
You can use this info in the `api.mustache` file.