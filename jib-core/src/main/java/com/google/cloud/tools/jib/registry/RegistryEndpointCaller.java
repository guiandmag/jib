/*
 * Copyright 2018 Google LLC. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.tools.jib.registry;

import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpResponseException;
import com.google.api.client.http.HttpStatusCodes;
import com.google.cloud.tools.jib.http.Authorization;
import com.google.cloud.tools.jib.http.Connection;
import com.google.cloud.tools.jib.http.Request;
import com.google.cloud.tools.jib.http.Response;
import com.google.cloud.tools.jib.json.JsonTemplateMapper;
import com.google.cloud.tools.jib.registry.json.ErrorEntryTemplate;
import com.google.cloud.tools.jib.registry.json.ErrorResponseTemplate;
import com.google.common.annotations.VisibleForTesting;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.function.Function;
import javax.annotation.Nullable;
import javax.net.ssl.SSLPeerUnverifiedException;
import org.apache.http.NoHttpResponseException;
import org.apache.http.conn.HttpHostConnectException;

/**
 * Makes requests to a registry endpoint.
 *
 * @param <T> the type returned by calling the endpoint
 */
class RegistryEndpointCaller<T> {

  /**
   * @see <a
   *     href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/308">https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/308</a>
   */
  @VisibleForTesting static final int STATUS_CODE_PERMANENT_REDIRECT = 308;

  private static final String DEFAULT_PROTOCOL = "https";

  private final URL initialRequestUrl;
  private final String userAgent;
  private final RegistryEndpointProvider<T> registryEndpointProvider;
  @Nullable private final Authorization authorization;
  private final RegistryEndpointRequestProperties registryEndpointRequestProperties;
  private final boolean allowInsecureRegistries;

  /** Makes a {@link Connection} to the specified {@link URL}. */
  private final Function<URL, Connection> connectionFactory;

  /** Makes an insecure {@link Connection} to the specified {@link URL}. */
  @Nullable private Function<URL, Connection> insecureConnectionFactory;

  /**
   * Constructs with parameters for making the request.
   *
   * @param userAgent {@code User-Agent} header to send with the request
   * @param apiRouteBase the endpoint's API root, without the protocol
   * @param registryEndpointProvider the {@link RegistryEndpointProvider} to the endpoint
   * @param authorization optional authentication credentials to use
   * @param registryEndpointRequestProperties properties of the registry endpoint request
   * @param allowInsecureRegistries if {@code true}, insecure connections will be allowed
   * @throws MalformedURLException if the URL generated for the endpoint is malformed
   */
  RegistryEndpointCaller(
      String userAgent,
      String apiRouteBase,
      RegistryEndpointProvider<T> registryEndpointProvider,
      @Nullable Authorization authorization,
      RegistryEndpointRequestProperties registryEndpointRequestProperties,
      boolean allowInsecureRegistries)
      throws MalformedURLException {
    this(
        userAgent,
        apiRouteBase,
        registryEndpointProvider,
        authorization,
        registryEndpointRequestProperties,
        allowInsecureRegistries,
        Connection.getConnectionFactory(),
        null /* might never be used, so create lazily to delay throwing potential GeneralSecurityException */);
  }

  @VisibleForTesting
  RegistryEndpointCaller(
      String userAgent,
      String apiRouteBase,
      RegistryEndpointProvider<T> registryEndpointProvider,
      @Nullable Authorization authorization,
      RegistryEndpointRequestProperties registryEndpointRequestProperties,
      boolean allowInsecureRegistries,
      Function<URL, Connection> connectionFactory,
      @Nullable Function<URL, Connection> insecureConnectionFactory)
      throws MalformedURLException {
    this.initialRequestUrl =
        registryEndpointProvider.getApiRoute(DEFAULT_PROTOCOL + "://" + apiRouteBase);
    this.userAgent = userAgent;
    this.registryEndpointProvider = registryEndpointProvider;
    this.authorization = authorization;
    this.registryEndpointRequestProperties = registryEndpointRequestProperties;
    this.allowInsecureRegistries = allowInsecureRegistries;
    this.connectionFactory = connectionFactory;
    this.insecureConnectionFactory = insecureConnectionFactory;
  }

  /**
   * Makes the request to the endpoint.
   *
   * @return an object representing the response, or {@code null}
   * @throws IOException for most I/O exceptions when making the request
   * @throws RegistryException for known exceptions when interacting with the registry
   */
  @Nullable
  T call() throws IOException, RegistryException {
    return call(initialRequestUrl);
  }

  /**
   * Calls the registry endpoint with a certain {@link URL}.
   *
   * @param url the endpoint URL to call
   * @return an object representing the response, or {@code null}
   * @throws IOException for most I/O exceptions when making the request
   * @throws RegistryException for known exceptions when interacting with the registry
   */
  @VisibleForTesting
  @Nullable
  T call(URL url) throws IOException, RegistryException {
    boolean isHttpProtocol = "http".equals(url.getProtocol());
    if (!allowInsecureRegistries && isHttpProtocol) {
      throw new InsecureRegistryException(url);
    }
    // Only sends authorization if using HTTPS or explicitly forcing over HTTP.
    boolean sendCredentials = !isHttpProtocol || Boolean.getBoolean("sendCredentialsOverHttp");

    try (Connection connection = connectionFactory.apply(url)) {
      Request.Builder requestBuilder =
          Request.builder()
              .setUserAgent(userAgent)
              .setHttpTimeout(Integer.getInteger("jib.httpTimeout"))
              .setAccept(registryEndpointProvider.getAccept())
              .setBody(registryEndpointProvider.getContent());
      if (sendCredentials) {
        requestBuilder.setAuthorization(authorization);
      }
      Response response =
          connection.send(registryEndpointProvider.getHttpMethod(), requestBuilder.build());

      return registryEndpointProvider.handleResponse(response);

    } catch (HttpResponseException ex) {
      // First, see if the endpoint provider handles an exception as an expected response.
      try {
        return registryEndpointProvider.handleHttpResponseException(ex);

      } catch (HttpResponseException httpResponseException) {
        if (httpResponseException.getStatusCode() == HttpStatusCodes.STATUS_CODE_BAD_REQUEST
            || httpResponseException.getStatusCode() == HttpStatusCodes.STATUS_CODE_NOT_FOUND
            || httpResponseException.getStatusCode()
                == HttpStatusCodes.STATUS_CODE_METHOD_NOT_ALLOWED) {
          // The name or reference was invalid.
          ErrorResponseTemplate errorResponse =
              JsonTemplateMapper.readJson(
                  httpResponseException.getContent(), ErrorResponseTemplate.class);
          RegistryErrorExceptionBuilder registryErrorExceptionBuilder =
              new RegistryErrorExceptionBuilder(
                  registryEndpointProvider.getActionDescription(), httpResponseException);
          for (ErrorEntryTemplate errorEntry : errorResponse.getErrors()) {
            registryErrorExceptionBuilder.addReason(errorEntry);
          }

          throw registryErrorExceptionBuilder.build();

        } else if (httpResponseException.getStatusCode() == HttpStatusCodes.STATUS_CODE_FORBIDDEN) {
          throw new RegistryUnauthorizedException(
              registryEndpointRequestProperties.getServerUrl(),
              registryEndpointRequestProperties.getImageName(),
              httpResponseException);

        } else if (httpResponseException.getStatusCode()
            == HttpStatusCodes.STATUS_CODE_UNAUTHORIZED) {
          if (sendCredentials) {
            // Credentials are either missing or wrong.
            throw new RegistryUnauthorizedException(
                registryEndpointRequestProperties.getServerUrl(),
                registryEndpointRequestProperties.getImageName(),
                httpResponseException);
          } else {
            throw new RegistryCredentialsNotSentException(
                registryEndpointRequestProperties.getServerUrl(),
                registryEndpointRequestProperties.getImageName());
          }

        } else if (httpResponseException.getStatusCode()
                == HttpStatusCodes.STATUS_CODE_TEMPORARY_REDIRECT
            || httpResponseException.getStatusCode()
                == HttpStatusCodes.STATUS_CODE_MOVED_PERMANENTLY
            || httpResponseException.getStatusCode() == STATUS_CODE_PERMANENT_REDIRECT) {
          // 'Location' header can be relative or absolute.
          URL redirectLocation = new URL(url, httpResponseException.getHeaders().getLocation());
          return call(redirectLocation);

        } else {
          // Unknown
          throw httpResponseException;
        }
      }

    } catch (HttpHostConnectException | SSLPeerUnverifiedException ex) {
      // Tries to call with HTTP protocol if HTTPS failed to connect.
      // Note that this will not succeed if 'allowInsecureRegistries' is false.
      if ("https".equals(url.getProtocol())) {
        GenericUrl httpUrl = new GenericUrl(url);
        httpUrl.setScheme("http");
        return call(httpUrl.toURL());
      }

      throw ex;

    } catch (NoHttpResponseException ex) {
      throw new RegistryNoResponseException(ex);
    }
  }
}
