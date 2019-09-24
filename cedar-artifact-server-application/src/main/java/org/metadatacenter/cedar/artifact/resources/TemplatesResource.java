package org.metadatacenter.cedar.artifact.resources;

import com.codahale.metrics.annotation.Timed;
import com.fasterxml.jackson.databind.JsonNode;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.constant.CustomHttpConstants;
import org.metadatacenter.constant.HttpConstants;
import org.metadatacenter.constant.LinkedData;
import org.metadatacenter.error.CedarErrorKey;
import org.metadatacenter.error.CedarErrorReasonKey;
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
import org.metadatacenter.server.service.TemplateInstanceService;
import org.metadatacenter.server.service.TemplateService;
import org.metadatacenter.util.ModelUtil;
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

@Path("/templates")
@Produces(MediaType.APPLICATION_JSON)
public class TemplatesResource extends AbstractArtifactServerResource {

  private static final Logger logger = LoggerFactory.getLogger(TemplatesResource.class);

  private final TemplateService<String, JsonNode> templateService;
  private final TemplateInstanceService<String, JsonNode> templateInstanceService;

  protected static List<String> FIELD_NAMES_SUMMARY_LIST;

  public TemplatesResource(CedarConfig cedarConfig, TemplateService<String, JsonNode> templateService,
                           TemplateInstanceService<String, JsonNode> templateInstanceService) {
    super(cedarConfig);
    this.templateService = templateService;
    this.templateInstanceService = templateInstanceService;
    FIELD_NAMES_SUMMARY_LIST = new ArrayList<>();
    FIELD_NAMES_SUMMARY_LIST.addAll(cedarConfig.getArtifactRESTAPI().getSummaries().getTemplate().getFields());
  }

  @POST
  @Timed
  public Response createTemplate() throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_CREATE);
    c.must(c.request().getRequestBody()).be(NonEmpty);

    JsonNode template = c.request().getRequestBody().asJson();

    // This is checked on resource:
    // enforceMandatoryNullOrMissingId(template, CedarResourceType.TEMPLATE, CedarErrorKey.TEMPLATE_NOT_CREATED);
    enforceMandatoryName(template, CedarResourceType.TEMPLATE, CedarErrorKey.TEMPLATE_NOT_CREATED);

    ProvenanceInfo pi = provenanceUtil.build(c.getCedarUser());
    setProvenanceAndId(CedarResourceType.TEMPLATE, template, pi);

    Response response = null;
    if (cedarConfig.getValidationConfig().isEnabled()) {
      ValidationReport validationReport = validateTemplate(template);
      ReportUtils.outputLogger(logger, validationReport, true);
      String validationStatus = validationReport.getValidationStatus();
      if (validationStatus.equals(CedarValidationReport.IS_VALID)) {
        response = storeTemplateInDatabase(template, pi);
      } else {
        response = CedarResponse.badRequest()
            .header(CustomHttpConstants.HEADER_CEDAR_VALIDATION_STATUS, CedarValidationReport.IS_INVALID)
            .build();
      }
    } else {
      response = storeTemplateInDatabase(template, pi);
    }
    return response;
  }

  private Response storeTemplateInDatabase(JsonNode template, ProvenanceInfo pi) {
    try {
      ModelUtil.ensureFieldIdsRecursively(template, pi, provenanceUtil, linkedDataUtil);
      JsonNode createdTemplate = templateService.createTemplate(template);
      MongoUtils.removeIdField(createdTemplate);
      String id = createdTemplate.get(LinkedData.ID).asText();
      URI createdTemplateUri = CedarUrlUtil.getIdURI(uriInfo, id);
      return CedarResponse.created(createdTemplateUri)
          .header(CustomHttpConstants.HEADER_CEDAR_VALIDATION_STATUS, CedarValidationReport.IS_VALID)
          .entity(createdTemplate)
          .build();
    } catch (IOException e) {
      return CedarResponse.internalServerError()
          .errorKey(CedarErrorKey.TEMPLATE_NOT_CREATED)
          .errorMessage("The artifact can not be created")
          .exception(e)
          .build();
    }
  }

  @GET
  @Timed
  @Path("/{id}")
  public Response findTemplate(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_READ);
    c.must(id).be(ValidUrl);

    JsonNode template;
    try {
      template = templateService.findTemplate(id);
    } catch (IOException e) {
      return CedarResponse.internalServerError()
          .id(id)
          .errorKey(CedarErrorKey.TEMPLATE_NOT_FOUND)
          .errorMessage("The artifact can not be found by id:" + id)
          .exception(e)
          .build();
    }
    if (template == null) {
      return CedarResponse.notFound()
          .id(id)
          .errorKey(CedarErrorKey.TEMPLATE_NOT_FOUND)
          .errorMessage("The artifact can not be found by id:" + id)
          .build();
    } else {
      MongoUtils.removeIdField(template);
      return Response.ok().entity(template).build();
    }
  }

  @GET
  @Timed
  public Response findAllTemplates(@QueryParam(QP_LIMIT) Optional<Integer> limitParam,
                                   @QueryParam(QP_OFFSET) Optional<Integer> offsetParam,
                                   @QueryParam(QP_SUMMARY) Optional<Boolean> summaryParam,
                                   @QueryParam(QP_FIELD_NAMES) Optional<String> fieldNamesParam) throws
      CedarException {

    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_READ);

    PagedQuery pagedQuery = new PagedQuery(cedarConfig.getArtifactRESTAPI().getPagination())
        .limit(limitParam)
        .offset(offsetParam);
    pagedQuery.validate();

    Integer limit = pagedQuery.getLimit();
    Integer offset = pagedQuery.getOffset();
    Boolean summary = ensureSummary(summaryParam);

    List<String> fieldNameList = getAndCheckFieldNames(fieldNamesParam, summary);
    Map<String, Object> r = new HashMap<>();
    List<JsonNode> templates = null;
    try {
      if (summary) {
        templates = templateService.findAllTemplates(limit, offset, FIELD_NAMES_SUMMARY_LIST, FieldNameInEx.INCLUDE);
      } else if (fieldNameList != null) {
        templates = templateService.findAllTemplates(limit, offset, fieldNameList, FieldNameInEx.INCLUDE);
      } else {
        templates = templateService.findAllTemplates(limit, offset, FIELD_NAMES_EXCLUSION_LIST, FieldNameInEx.EXCLUDE);
      }
    } catch (IOException e) {
      return CedarResponse.internalServerError()
          .errorKey(CedarErrorKey.TEMPLATES_NOT_LISTED)
          .errorMessage("The templates can not be listed")
          .exception(e)
          .build();
    }
    long total = templateService.count();
    checkPagingParametersAgainstTotal(offset, total);

    String absoluteUrl = uriInfo.getAbsolutePathBuilder().build().toString();
    String linkHeader = LinkHeaderUtil.getPagingLinkHeader(absoluteUrl, total, limit, offset);
    Response.ResponseBuilder responseBuilder = Response.ok().entity(templates);
    responseBuilder.header(CustomHttpConstants.HEADER_TOTAL_COUNT, String.valueOf(total));
    if (!linkHeader.isEmpty()) {
      responseBuilder.header(HttpConstants.HTTP_HEADER_LINK, linkHeader);
    }
    return responseBuilder.build();
  }

  @PUT
  @Timed
  @Path("/{id}")
  public Response updateTemplate(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(id).be(ValidUrl);
    c.must(c.user()).have(CedarPermission.TEMPLATE_UPDATE);
    c.must(c.request().getRequestBody()).be(NonEmpty);

    JsonNode newTemplate = c.request().getRequestBody().asJson();

    enforceMandatoryFieldsInPut(id, newTemplate, CedarResourceType.TEMPLATE, CedarErrorKey.TEMPLATE_NOT_UPDATED);
    enforceMandatoryName(newTemplate, CedarResourceType.TEMPLATE, CedarErrorKey.TEMPLATE_NOT_UPDATED);

    ProvenanceInfo pi = provenanceUtil.build(c.getCedarUser());
    provenanceUtil.patchProvenanceInfo(newTemplate, pi);

    Response response = null;
    if (cedarConfig.getValidationConfig().isEnabled()) {
      ValidationReport validationReport = validateTemplate(newTemplate);
      ReportUtils.outputLogger(logger, validationReport, true);
      String validationStatus = validationReport.getValidationStatus();
      if (validationStatus.equals(CedarValidationReport.IS_VALID)) {
        response = updateTemplateInDatabase(id, newTemplate, pi, c);
      } else {
        response = CedarResponse.badRequest()
            .header(CustomHttpConstants.HEADER_CEDAR_VALIDATION_STATUS, CedarValidationReport.IS_INVALID)
            .build();
      }
    } else {
      response = updateTemplateInDatabase(id, newTemplate, pi, c);
    }
    return response;
  }

  private Response updateTemplateInDatabase(String templateId, JsonNode updatedTemplate, ProvenanceInfo pi,
                                            CedarRequestContext c) throws CedarException {
    JsonNode outputTemplate = null;
    CreateOrUpdate createOrUpdate = null;
    try {
      JsonNode currentTemplate = templateService.findTemplate(templateId);
      ModelUtil.ensureFieldIdsRecursively(updatedTemplate, pi, provenanceUtil, linkedDataUtil);
      if (currentTemplate != null) {
        createOrUpdate = CreateOrUpdate.UPDATE;
        outputTemplate = templateService.updateTemplate(templateId, updatedTemplate);
      } else {
        c.must(templateId).be(ValidId);
        createOrUpdate = CreateOrUpdate.CREATE;
        outputTemplate = templateService.createTemplate(updatedTemplate);
      }
      MongoUtils.removeIdField(outputTemplate);
      CedarResponse.CedarResponseBuilder responseBuilder = null;
      if (createOrUpdate == CreateOrUpdate.UPDATE) {
        responseBuilder = CedarResponse.ok();
      } else {
        URI createdTemplateUri = CedarUrlUtil.getURI(uriInfo);
        responseBuilder = CedarResponse.created(createdTemplateUri);
      }
      return responseBuilder
          .header(CustomHttpConstants.HEADER_CEDAR_VALIDATION_STATUS, CedarValidationReport.IS_VALID)
          .entity(outputTemplate)
          .build();
    } catch (IOException | ArtifactServerResourceNotFoundException e) {
      CedarResponse.CedarResponseBuilder responseBuilder = CedarResponse.internalServerError()
          .id(templateId)
          .exception(e);
      if (createOrUpdate == CreateOrUpdate.CREATE) {
        responseBuilder
            .errorKey(CedarErrorKey.TEMPLATE_NOT_CREATED)
            .errorMessage("The artifact can not be created using id:" + templateId);
      } else if (createOrUpdate == CreateOrUpdate.UPDATE) {
        responseBuilder
            .errorKey(CedarErrorKey.TEMPLATE_NOT_UPDATED)
            .errorMessage("The artifact can not be updated by id:" + templateId);
      }
      return responseBuilder.build();
    }
  }

  @DELETE
  @Timed
  @Path("/{id}")
  public Response deleteTemplate(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_DELETE);
    c.must(id).be(ValidUrl);

    long referenceCount = templateInstanceService.countReferencingTemplate(id);

    if (referenceCount != 0) {
      return CedarResponse.badRequest()
          .id(id)
          .errorKey(CedarErrorKey.TEMPLATE_NOT_DELETED)
          .errorReasonKey(CedarErrorReasonKey.TEMPLATE_REFERENCED_IN_INSTANCES)
          .errorMessage("The artifact can not be deleted since there are instances using it")
          .parameter("referenceCount", referenceCount)
          .build();
    }

    try {
      templateService.deleteTemplate(id);
    } catch (ArtifactServerResourceNotFoundException e) {
      return CedarResponse.notFound()
          .id(id)
          .errorKey(CedarErrorKey.TEMPLATE_NOT_FOUND)
          .errorMessage("The artifact can not be found by id:" + id)
          .exception(e)
          .build();
    } catch (IOException e) {
      return CedarResponse.internalServerError()
          .id(id)
          .errorKey(CedarErrorKey.TEMPLATE_NOT_DELETED)
          .errorMessage("The artifact can not be deleted by id:" + id)
          .exception(e)
          .build();
    }
    return CedarResponse.noContent().build();
  }
}
