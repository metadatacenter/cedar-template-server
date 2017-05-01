package org.metadatacenter.cedar.template.resources.crud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import junitparams.naming.TestCaseName;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.metadatacenter.cedar.template.resources.utils.TestUtil;
import org.metadatacenter.model.CedarNodeType;

import javax.ws.rs.client.Entity;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.net.URLEncoder;

import static org.metadatacenter.cedar.template.resources.utils.TestConstants.*;

@RunWith(JUnitParamsRunner.class)
public class UpdateResourceTest extends AbstractResourceCrudTest {

  /**
   * 'UPDATE' TESTS
   */

  @Test
  @TestCaseName(TEST_NAME_PATTERN)
  @Parameters(method = "getCommonParams1")
  public void updateResourceTest(JsonNode sampleResource, CedarNodeType resourceType) {
    String url = TestUtil.getResourceUrlRoute(baseTestUrl, resourceType);
    sampleResource = setSchemaIsBasedOn(sampleTemplate, sampleResource, resourceType);
    // Create a resource
    try {
      JsonNode createdResource = createResource(sampleResource, resourceType);
      createdResources.put(createdResource.get(ID_FIELD).asText(), resourceType);
      String createdResourceId = createdResource.get(ID_FIELD).asText();
      // Update the resource
      String fieldName = "title";
      String fieldNewValue = "This is a new title";
      JsonNode updatedResource = ((ObjectNode) createdResource).put(fieldName, fieldNewValue);
      // Service invocation - Update
      Response responseUpdate = testClient.target(url + "/" + URLEncoder.encode(createdResourceId, "UTF-8")).
          request().header("Authorization", authHeader).put(Entity.json(updatedResource));
      // Check HTTP response
      Assert.assertEquals(Response.Status.OK.getStatusCode(), responseUpdate.getStatus());
      // Retrieve updated element
      Response responseFind = testClient.target(url + "/" + URLEncoder.encode(createdResourceId, "UTF-8")).
          request().header("Authorization", authHeader).get();
      JsonNode actual = responseFind.readEntity(JsonNode.class);
      // Check that the modifications have been done correctly
      Assert.assertNotNull(actual.get(fieldName));
      Assert.assertEquals(fieldNewValue, actual.get(fieldName).asText());
      // Check that all the other fields contain the expected values
      ((ObjectNode) createdResource).remove(fieldName);
      ((ObjectNode) actual).remove(fieldName);
      // Remove the lastUpdatedOn field
      ((ObjectNode) createdResource).remove(LAST_UPDATED_ON_FIELD);
      ((ObjectNode) actual).remove(LAST_UPDATED_ON_FIELD);
      Assert.assertEquals(createdResource, actual);
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

}
