package org.metadatacenter.cedar.artifact.resources;

import com.codahale.metrics.annotation.Timed;
import com.fasterxml.jackson.databind.JsonNode;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.constant.CustomHttpConstants;
import org.metadatacenter.constant.HttpConstants;
import org.metadatacenter.constant.LinkedData;
import org.metadatacenter.error.CedarErrorKey;
import org.metadatacenter.exception.ArtifactServerResourceNotFoundException;
import org.metadatacenter.model.CedarResourceType;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.model.CreateOrUpdate;
import org.metadatacenter.model.validation.report.CedarValidationReport;
import org.metadatacenter.model.validation.report.ReportUtils;
import org.metadatacenter.model.validation.report.ValidationReport;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.server.model.provenance.ProvenanceInfo;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.metadatacenter.server.service.FieldNameInEx;
import org.metadatacenter.server.service.TemplateFieldService;
import org.metadatacenter.util.http.CedarResponse;
import org.metadatacenter.util.http.CedarUrlUtil;
import org.metadatacenter.util.http.LinkHeaderUtil;
import org.metadatacenter.util.http.PagedQuery;
import org.metadatacenter.util.mongo.MongoUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.net.URI;
import java.util.*;

import static org.metadatacenter.constant.CedarPathParameters.PP_ID;
import static org.metadatacenter.constant.CedarQueryParameters.*;
import static org.metadatacenter.rest.assertion.GenericAssertions.*;

@Path("/template-fields")
@Produces(MediaType.APPLICATION_JSON)
public class TemplateFieldsResource extends AbstractArtifactServerResource {

  private static final Logger logger = LoggerFactory.getLogger(TemplateFieldsResource.class);

  private static TemplateFieldService<String, JsonNode> templateFieldService;

  protected static List<String> FIELD_NAMES_SUMMARY_LIST;

  public TemplateFieldsResource(CedarConfig cedarConfig, TemplateFieldService<String, JsonNode> templateFieldService) {
    super(cedarConfig);
    TemplateFieldsResource.templateFieldService = templateFieldService;
    FIELD_NAMES_SUMMARY_LIST = new ArrayList<>();
    FIELD_NAMES_SUMMARY_LIST.addAll(cedarConfig.getArtifactRESTAPI().getSummaries().getField().getFields());
  }

  @POST
  @Timed
  public Response createTemplateField() throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_FIELD_CREATE);
    c.must(c.request().getRequestBody()).be(NonEmpty);

    JsonNode templateField = c.request().getRequestBody().asJson();

    // This is checked on resource:
    // enforceMandatoryNullOrMissingId(templateField, CedarResourceType.FIELD, CedarErrorKey.TEMPLATE_FIELD_NOT_CREATED);
    enforceMandatoryName(templateField, CedarResourceType.FIELD, CedarErrorKey.TEMPLATE_FIELD_NOT_CREATED);

    ProvenanceInfo pi = provenanceUtil.build(c.getCedarUser());
    setProvenanceAndId(CedarResourceType.FIELD, templateField, pi);

    Response response = null;
    if (cedarConfig.getValidationConfig().isEnabled()) {
      ValidationReport validationReport = validateTemplateField(templateField);
      ReportUtils.outputLogger(logger, validationReport, true);
      String validationStatus = validationReport.getValidationStatus();
      if (validationStatus.equals(CedarValidationReport.IS_VALID)) {
        response = storeTemplateFieldInDatabase(templateField);
      } else {
        response = CedarResponse.badRequest()
            .header(CustomHttpConstants.HEADER_CEDAR_VALIDATION_STATUS, CedarValidationReport.IS_INVALID)
            .build();
      }
    } else {
      response = storeTemplateFieldInDatabase(templateField);
    }
    return response;
  }

  private Response storeTemplateFieldInDatabase(JsonNode templateField) {
    try {
      JsonNode createdTemplateField = templateFieldService.createTemplateField(templateField);
      MongoUtils.removeIdField(createdTemplateField);
      String id = createdTemplateField.get(LinkedData.ID).asText();
      URI createdFieldUri = CedarUrlUtil.getIdURI(uriInfo, id);
      return CedarResponse.created(createdFieldUri)
          .header(CustomHttpConstants.HEADER_CEDAR_VALIDATION_STATUS, CedarValidationReport.IS_VALID)
          .entity(createdTemplateField).build();
    } catch (IOException e) {
      return CedarResponse.internalServerError()
          .errorKey(CedarErrorKey.TEMPLATE_FIELD_NOT_CREATED)
          .errorMessage("The artifact field can not be created")
          .exception(e)
          .build();
    }
  }

  @GET
  @Timed
  @Path("/{id}")
  public Response findTemplateField(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_FIELD_READ);
    c.must(id).be(ValidUrl);

    JsonNode templateField = null;
    try {
      templateField = templateFieldService.findTemplateField(id);
    } catch (IOException e) {
      return CedarResponse.internalServerError()
          .id(id)
          .errorKey(CedarErrorKey.TEMPLATE_FIELD_NOT_FOUND)
          .errorMessage("The artifact field can not be found by id:" + id)
          .exception(e)
          .build();
    }
    if (templateField == null) {
      return CedarResponse.notFound()
          .id(id)
          .errorKey(CedarErrorKey.TEMPLATE_FIELD_NOT_FOUND)
          .errorMessage("The artifact field can not be found by id:" + id)
          .build();
    } else {
      MongoUtils.removeIdField(templateField);
      return Response.ok().entity(templateField).build();
    }
  }

  @GET
  @Timed
  public Response findAllTemplateFields(@QueryParam(QP_LIMIT) Optional<Integer> limitParam,
                                        @QueryParam(QP_OFFSET) Optional<Integer> offsetParam,
                                        @QueryParam(QP_SUMMARY) Optional<Boolean> summaryParam,
                                        @QueryParam(QP_FIELD_NAMES) Optional<String> fieldNamesParam) throws
      CedarException {

    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_FIELD_READ);

    PagedQuery pagedQuery = new PagedQuery(cedarConfig.getArtifactRESTAPI().getPagination())
        .limit(limitParam)
        .offset(offsetParam);
    pagedQuery.validate();

    Integer limit = pagedQuery.getLimit();
    Integer offset = pagedQuery.getOffset();
    Boolean summary = ensureSummary(summaryParam);

    List<String> fieldNameList = getAndCheckFieldNames(fieldNamesParam, summary);
    Map<String, Object> r = new HashMap<>();
    List<JsonNode> fields = null;
    try {
      if (summary) {
        fields = templateFieldService.findAllTemplateFields(limit, offset, FIELD_NAMES_SUMMARY_LIST, FieldNameInEx
            .INCLUDE);
      } else if (fieldNameList != null) {
        fields = templateFieldService.findAllTemplateFields(limit, offset, fieldNameList, FieldNameInEx.INCLUDE);
      } else {
        fields = templateFieldService.findAllTemplateFields(limit, offset, FIELD_NAMES_EXCLUSION_LIST, FieldNameInEx
            .EXCLUDE);
      }
    } catch (IOException e) {
      return CedarResponse.internalServerError()
          .errorKey(CedarErrorKey.TEMPLATE_FIELDS_NOT_LISTED)
          .errorMessage("The artifact fields can not be listed")
          .exception(e)
          .build();
    }
    long total = templateFieldService.count();
    checkPagingParametersAgainstTotal(offset, total);

    String absoluteUrl = uriInfo.getAbsolutePathBuilder().build().toString();
    String linkHeader = LinkHeaderUtil.getPagingLinkHeader(absoluteUrl, total, limit, offset);
    Response.ResponseBuilder responseBuilder = Response.ok().entity(fields);
    responseBuilder.header(CustomHttpConstants.HEADER_TOTAL_COUNT, String.valueOf(total));
    if (!linkHeader.isEmpty()) {
      responseBuilder.header(HttpConstants.HTTP_HEADER_LINK, linkHeader);
    }
    return responseBuilder.build();
  }

  @PUT
  @Timed
  @Path("/{id}")
  public Response updateTemplateField(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(id).be(ValidUrl);
    c.must(c.user()).have(CedarPermission.TEMPLATE_FIELD_UPDATE);
    c.must(c.request().getRequestBody()).be(NonEmpty);

    JsonNode newField = c.request().getRequestBody().asJson();

    enforceMandatoryFieldsInPut(id, newField, CedarResourceType.FIELD, CedarErrorKey.TEMPLATE_FIELD_NOT_UPDATED);
    enforceMandatoryName(newField, CedarResourceType.FIELD, CedarErrorKey.TEMPLATE_FIELD_NOT_UPDATED);

    ProvenanceInfo pi = provenanceUtil.build(c.getCedarUser());
    provenanceUtil.patchProvenanceInfo(newField, pi);

    Response response = null;
    if (cedarConfig.getValidationConfig().isEnabled()) {
      ValidationReport validationReport = validateTemplateField(newField);
      ReportUtils.outputLogger(logger, validationReport, true);
      String validationStatus = validationReport.getValidationStatus();
      if (validationStatus.equals(CedarValidationReport.IS_VALID)) {
        response = updateTemplateFieldInDatabase(id, newField, pi, c);
      } else {
        response = CedarResponse.badRequest()
            .header(CustomHttpConstants.HEADER_CEDAR_VALIDATION_STATUS, CedarValidationReport.IS_INVALID)
            .build();
      }
    } else {
      response = updateTemplateFieldInDatabase(id, newField, pi, c);
    }
    return response;
  }

  private Response updateTemplateFieldInDatabase(String fieldId, JsonNode updatedField, ProvenanceInfo pi,
                                                 CedarRequestContext c) throws CedarException {
    JsonNode outputTemplateField = null;
    CreateOrUpdate createOrUpdate = null;
    try {
      JsonNode currentTemplateField = templateFieldService.findTemplateField(fieldId);
      if (currentTemplateField != null) {
        createOrUpdate = CreateOrUpdate.UPDATE;
        outputTemplateField = templateFieldService.updateTemplateField(fieldId, updatedField);
      } else {
        c.must(fieldId).be(ValidId);
        createOrUpdate = CreateOrUpdate.CREATE;
        outputTemplateField = templateFieldService.createTemplateField(updatedField);
      }
      MongoUtils.removeIdField(outputTemplateField);
      CedarResponse.CedarResponseBuilder responseBuilder = null;
      if (createOrUpdate == CreateOrUpdate.UPDATE) {
        responseBuilder = CedarResponse.ok();
      } else {
        URI createdTemplateFieldUri = CedarUrlUtil.getURI(uriInfo);
        responseBuilder = CedarResponse.created(createdTemplateFieldUri);
      }
      return responseBuilder
          .header(CustomHttpConstants.HEADER_CEDAR_VALIDATION_STATUS, CedarValidationReport.IS_VALID)
          .entity(outputTemplateField)
          .build();
    } catch (IOException | ArtifactServerResourceNotFoundException e) {
      CedarResponse.CedarResponseBuilder responseBuilder = CedarResponse.internalServerError()
          .id(fieldId)
          .exception(e);
      if (createOrUpdate == CreateOrUpdate.CREATE) {
        responseBuilder
            .errorKey(CedarErrorKey.TEMPLATE_FIELD_NOT_CREATED)
            .errorMessage("The artifact field can not be created using id:" + fieldId);
      } else if (createOrUpdate == CreateOrUpdate.UPDATE) {
        responseBuilder
            .errorKey(CedarErrorKey.TEMPLATE_FIELD_NOT_UPDATED)
            .errorMessage("The artifact field can not be updated by id:" + fieldId);
      }
      return responseBuilder.build();
    }
  }

  @DELETE
  @Timed
  @Path("/{id}")
  public Response deleteTemplateField(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_FIELD_DELETE);
    c.must(id).be(ValidUrl);

    try {
      templateFieldService.deleteTemplateField(id);
    } catch (ArtifactServerResourceNotFoundException e) {
      return CedarResponse.notFound()
          .id(id)
          .errorKey(CedarErrorKey.TEMPLATE_FIELD_NOT_FOUND)
          .errorMessage("The artifact field can not be found by id:" + id)
          .exception(e)
          .build();
    } catch (IOException e) {
      return CedarResponse.internalServerError()
          .id(id)
          .errorKey(CedarErrorKey.TEMPLATE_FIELD_NOT_DELETED)
          .errorMessage("The artifact field can not be deleted by id:" + id)
          .exception(e)
          .build();
    }
    return CedarResponse.noContent().build();
  }
}
