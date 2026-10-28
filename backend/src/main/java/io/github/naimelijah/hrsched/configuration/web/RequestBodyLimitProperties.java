package io.github.naimelijah.hrsched.configuration.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The ceiling on a JSON request body, refused at startup unless it is a positive number of bytes.
 *
 * <p>Nothing reads this record; it exists to refuse. Jackson receives the value through {@code
 * spring.jackson.factory.constraints.read.max-document-length} in {@code application.properties},
 * and Jackson reads a maximum of zero or less as no limit at all. So a deployment that set 0, to
 * forbid bodies or by mistake, would start cleanly with sign-in, which takes no token, buffering
 * whatever it was sent (issue #250). Binding the value here first is what stops that start.
 *
 * @param maxJsonDocumentBytes the longest JSON request body accepted, in bytes
 * @throws IllegalArgumentException if the limit is zero or negative
 */
@ConfigurationProperties("hrsched.web")
public record RequestBodyLimitProperties(long maxJsonDocumentBytes) {

  public RequestBodyLimitProperties {
    if (maxJsonDocumentBytes <= 0) {
      throw new IllegalArgumentException(
          "hrsched.web.max-json-document-bytes must be a positive number of bytes, because Jackson"
              + " reads 0 or less as no limit at all, but is "
              + maxJsonDocumentBytes);
    }
  }
}
