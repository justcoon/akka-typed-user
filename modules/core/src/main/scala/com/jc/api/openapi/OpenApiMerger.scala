package com.jc.api.openapi

import io.swagger.v3.oas.models.{ Components, OpenAPI, Paths }

object OpenApiMerger {

  def merge(o1: OpenAPI, o2: OpenAPI): OpenAPI = {
    if (o2.getTags != null) {
      o2.getTags.forEach(o1.addTagsItem)
    }

    if (o2.getSecurity != null) {
      o2.getSecurity.forEach(o1.addSecurityItem)
    }

    if (o2.getServers != null) {
      o2.getServers.forEach(o1.addServersItem)
    }

//    if (o2.getInfo != null) {
//
//    }
//
//    if (o2.getExternalDocs != null) {
//
//    }

    if (o2.getExtensions != null) {
      o2.getExtensions.forEach { (k, v) =>
        o1.addExtension(k, v)
      }
    }

    if (o2.getPaths != null) {
      if (o1.getPaths == null) {
        o1.paths(new Paths)
      }

      o2.getPaths.forEach { (k, v) =>
        o1.path(k, v)
      }

      o2.getPaths.getExtensions.forEach { (k, v) =>
        o1.getPaths.addExtension(k, v)
      }
    }

    if (o2.getComponents != null) {
      if (o1.getComponents == null) {
        o1.components(new Components)
      }

      if (o2.getComponents.getSchemas != null)
        o2.getComponents.getSchemas.forEach { (k, v) =>
          o1.getComponents.addSchemas(k, v)
        }
      if (o2.getComponents.getResponses != null)
        o2.getComponents.getResponses.forEach { (k, v) =>
          o1.getComponents.addResponses(k, v)
        }
      if (o2.getComponents.getParameters != null)
        o2.getComponents.getParameters.forEach { (k, v) =>
          o1.getComponents.addParameters(k, v)
        }
      if (o2.getComponents.getExamples != null)
        o2.getComponents.getExamples.forEach { (k, v) =>
          o1.getComponents.addExamples(k, v)
        }
      if (o2.getComponents.getRequestBodies != null)
        o2.getComponents.getRequestBodies.forEach { (k, v) =>
          o1.getComponents.addRequestBodies(k, v)
        }
      if (o2.getComponents.getHeaders != null)
        o2.getComponents.getHeaders.forEach { (k, v) =>
          o1.getComponents.addHeaders(k, v)
        }
      if (o2.getComponents.getSecuritySchemes != null)
        o2.getComponents.getSecuritySchemes.forEach { (k, v) =>
          o1.getComponents.addSecuritySchemes(k, v)
        }
      if (o2.getComponents.getLinks != null)
        o2.getComponents.getLinks.forEach { (k, v) =>
          o1.getComponents.addLinks(k, v)
        }
      if (o2.getComponents.getCallbacks != null)
        o2.getComponents.getCallbacks.forEach { (k, v) =>
          o1.getComponents.addCallbacks(k, v)
        }
      if (o2.getComponents.getExtensions != null)
        o2.getComponents.getExtensions.forEach { (k, v) =>
          o1.getComponents.addExtension(k, v)
        }
    }

    o1
  }

  def merge(main: OpenAPI, others: Iterable[OpenAPI]): OpenAPI =
    others.foldLeft(main)(merge)

}
