package org.metadatacenter.cedar.template.resources;

import javax.annotation.Nonnull;

import static com.google.common.base.Preconditions.checkNotNull;

public class TestRequestUrls {

  public static final String SERVICE_BASE_URL = "http://localhost";

  public static String forCreatingTemplate(int portNumber) {
    return String.format("%s:%d/templates", SERVICE_BASE_URL, portNumber);
  }

  public static String forDeletingTemplate(int portNumber, @Nonnull String templateId) {
    checkNotNull(templateId);
    return String.format("%s:%d/templates/%s", SERVICE_BASE_URL, portNumber, templateId);
  }

  public static String forFindingTemplate(int portNumber, @Nonnull String templateId) {
    checkNotNull(templateId);
    return String.format("%s:%d/templates/%s", SERVICE_BASE_URL, portNumber, templateId);
  }

  public static String forCreatingInstances(int portNumber) {
    return String.format("%s:%d/template-instances", SERVICE_BASE_URL, portNumber);
  }

  public static String forDeletingInstance(int portNumber, @Nonnull String instanceId) {
    checkNotNull(instanceId);
    return String.format("%s:%d/template-instances/%s", SERVICE_BASE_URL, portNumber, instanceId);
  }

  public static String forFindingInstance(int portNumber, @Nonnull String instanceId, @Nonnull String formatType) {
    checkNotNull(instanceId);
    checkNotNull(formatType);
    return String.format("%s:%d/template-instances/%s?format=%s", SERVICE_BASE_URL, portNumber, instanceId, formatType);
  }

  public static String forValidatingTemplate(int portNumber) {
    return String.format("%s:%d/command/validate?resource_type=template", SERVICE_BASE_URL, portNumber);
  }

  public static String forValidatingElement(int portNumber) {
    return String.format("%s:%d/command/validate?resource_type=element", SERVICE_BASE_URL, portNumber);
  }

  public static String forValidatingField(int portNumber) {
    return String.format("%s:%d/command/validate?resource_type=field", SERVICE_BASE_URL, portNumber);
  }

  public static String forValidatingInstance(int portNumber) {
    return String.format("%s:%d/command/validate?resource_type=instance", SERVICE_BASE_URL, portNumber);
  }
}