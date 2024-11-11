# Onshape OpenAPI Utilities

This repository contains utilities for the generation and maintenance of Onshape REST API bindings. The set of utilities available includes:

- [go-oapi-codegen](go-oapi-codegen): A custom Go OpenAPI generator that supports polymorphism and other idioms common in the Onshape REST API
- [.github/workflows](.github/workflows): A set of GitHub workflows for downloading, processing, and generating bindings from the Onshape OpenAPI specification. In addition, scripts are included for generating bindings locally.

# Important note
- go-oapi-codegen-0.1.16.jar is supposed to be based off of openapi-generator v7.9, yet it produces undeterministic code output with redeclarations.