package org.metadatacenter.cedar.artifact.resources;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.metadatacenter.cedar.util.dw.CedarMicroserviceResource;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.constant.LinkedData;
import org.metadatacenter.error.CedarErrorKey;
import org.metadatacenter.error.CedarErrorPack;
import org.metadatacenter.exception.CedarBadRequestException;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.exception.CedarProcessingException;
import org.metadatacenter.exception.CedarRequestBodyMissingFieldException;
import org.metadatacenter.model.CedarResourceType;
import org.metadatacenter.model.core.CedarModelVocabulary;
import org.metadatacenter.model.validation.CedarValidator;
import org.metadatacenter.model.validation.ModelValidator;
import org.metadatacenter.model.validation.report.ValidationReport;
import org.metadatacenter.rest.exception.CedarAssertionException;
import org.metadatacenter.server.model.provenance.ProvenanceInfo;
import org.metadatacenter.server.service.TemplateService;
import org.metadatacenter.util.JsonPointerValuePair;
import org.metadatacenter.util.ModelUtil;
import org.metadatacenter.util.mongo.MongoUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static com.fasterxml.jackson.databind.node.JsonNodeType.NULL;

public class AbstractArtifactServerResource extends CedarMicroserviceResource {

  protected static List<String> FIELD_NAMES_EXCLUSION_LIST;

  protected AbstractArtifactServerResource(CedarConfig cedarConfig) {
    super(cedarConfig);
    FIELD_NAMES_EXCLUSION_LIST = new ArrayList<>();
    FIELD_NAMES_EXCLUSION_LIST.addAll(cedarConfig.getArtifactRESTAPI().getExcludedFields());
  }

  protected void setProvenanceAndId(CedarResourceType cedarResourceType, JsonNode element, ProvenanceInfo pi) {
    if ((element.get(LinkedData.ID) != null) && (!NULL.equals(element.get(LinkedData.ID).getNodeType()))) {
      throw new IllegalArgumentException("Specifying @id for new objects is not allowed");
    }
    provenanceUtil.addProvenanceInfo(element, pi);

    String id = linkedDataUtil.buildNewLinkedDataId(cedarResourceType);
    ((ObjectNode) element).put(LinkedData.ID, id);

    // add template-element-instance ids (only for instances)
    linkedDataUtil.addElementInstanceIds(element, cedarResourceType);
  }

  protected Boolean ensureSummary(Optional<Boolean> summary) {
    if (summary == null || !summary.isPresent()) {
      return false;
    } else {
      return summary.get();
    }
  }

  protected static List<String> getAndCheckFieldNames(Optional<String> fieldNames, boolean summary) throws
      CedarAssertionException {
    if (fieldNames != null && fieldNames.isPresent()) {
      if (summary == true) {
        throw new CedarAssertionException(
            "It is no allowed to specify parameter 'field_names' and also set 'summary' to true!");
      } else if (fieldNames.get().length() > 0) {
        return Arrays.asList(fieldNames.get().split(","));
      }
    }
    return null;
  }

  protected static void checkPagingParametersAgainstTotal(Integer offset, long total) throws CedarException {
    if (offset != 0 && offset > total - 1) {
      throw new CedarProcessingException(
          "Parameter 'offset' must be smaller than the total count of objects, which is " + total + "!")
          .parameter("offset", offset)
          .parameter("total", total);
    }
  }

  protected ValidationReport validateTemplate(JsonNode template) throws CedarException {
    try {
      return newModelValidator().validateTemplate(template);
    } catch (Exception e) {
      throw newCedarException(e.getMessage());
    }
  }

  protected ValidationReport validateTemplateElement(JsonNode templateElement) throws CedarException {
    try {
      return newModelValidator().validateTemplateElement(templateElement);
    } catch (Exception e) {
      throw newCedarException(e.getMessage());
    }
  }

  protected ValidationReport validateTemplateField(JsonNode templateField) throws CedarException {
    try {
      return newModelValidator().validateTemplateField(templateField);
    } catch (Exception e) {
      throw newCedarException(e.getMessage());
    }
  }

  protected ValidationReport validateTemplateInstance(JsonNode templateInstance, JsonNode instanceSchema)
      throws CedarException {
    try {
      return newModelValidator().validateTemplateInstance(templateInstance, instanceSchema);
    } catch (Exception e) {
      throw newCedarException(e.getMessage());
    }
  }

  private static ModelValidator newModelValidator() {
    return new CedarValidator();
  }

  protected static CedarException newCedarException(String message) {
    return new CedarException(message) {
    };
  }

  protected void enforceMandatoryNullOrMissingId(JsonNode jsonObject, CedarResourceType resourceType, CedarErrorKey errorKey)
      throws CedarBadRequestException {
    JsonNode idInRequestNode = jsonObject.get(LinkedData.ID);
    if (idInRequestNode != null && !idInRequestNode.isNull()) {
      String idInRequest = idInRequestNode.asText();
      if (idInRequest != null) {
        CedarErrorPack errorPack = new CedarErrorPack()
            .message("The " + resourceType.getValue() + " must not contain a non-null '" + LinkedData.ID + "' field!")
            .errorKey(errorKey)
            .parameter(LinkedData.ID, idInRequest);
        throw new CedarBadRequestException(errorPack);
      }
    }
  }

  protected void enforceMandatoryName(JsonNode jsonObject, CedarResourceType resourceType, CedarErrorKey errorKey)
      throws CedarBadRequestException {
    JsonPointerValuePair namePair = ModelUtil.extractNameFromResource(resourceType, jsonObject);
    if (namePair.hasEmptyValue()) {
      throw new CedarRequestBodyMissingFieldException(namePair.getPointer(), errorKey);
    }
  }

  protected void enforceMandatoryFieldsInPut(String id, JsonNode jsonObject, CedarResourceType resourceType, CedarErrorKey
      errorKey) throws CedarBadRequestException {
    JsonNode idInRequestNode = jsonObject.get(LinkedData.ID);
    String idInRequest = null;
    if (idInRequestNode != null && !idInRequestNode.isNull()) {
      idInRequest = idInRequestNode.asText();
    }
    if (idInRequest == null) {
      CedarErrorPack errorPack = new CedarErrorPack()
          .message("The " + resourceType.getValue() + " must contain a non-null '" + LinkedData.ID + "' field!")
          .errorKey(errorKey);
      throw new CedarBadRequestException(errorPack);
    }
    if (!idInRequest.equals(id)) {
      CedarErrorPack errorPack = new CedarErrorPack()
          .message("The @id in the body must match the id in the URL!")
          .parameter("idInURL", id)
          .parameter("idInBody", idInRequest)
          .errorKey(errorKey);
      throw new CedarBadRequestException(errorPack);
    }
  }


  protected static JsonNode getSchemaSource(TemplateService<String, JsonNode> templateService, JsonNode
      templateInstance) throws IOException, CedarException {
    checkInstanceSchemaExists(templateInstance);
    String templateRefId = templateInstance.get(CedarModelVocabulary.SCHEMA_IS_BASED_ON).asText();
    JsonNode template = templateService.findTemplate(templateRefId);
    if (template == null) {
      throw new CedarBadRequestException(
          new CedarErrorPack()
              .message("The artifact that this instance is based on can not be found.")
              .parameter(CedarModelVocabulary.SCHEMA_IS_BASED_ON, templateRefId)
              .errorKey(CedarErrorKey.INVALID_INPUT)
      );
    }
    MongoUtils.removeIdField(template);
    return template;
  }

  protected static JsonNode checkInstanceSchemaExists(JsonNode templateInstance) throws CedarException {
    JsonNode isBasedOnNode = templateInstance.path(CedarModelVocabulary.SCHEMA_IS_BASED_ON);
    if (isBasedOnNode.isMissingNode()) {
      throw new CedarRequestBodyMissingFieldException(CedarModelVocabulary.SCHEMA_IS_BASED_ON, CedarErrorKey
          .INVALID_INPUT);
    }
    return templateInstance;
  }

}
